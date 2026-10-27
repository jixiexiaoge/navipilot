package com.jixiexiaoge.drivingassist.navigation

import com.google.common.truth.Truth.assertThat
import com.jixiexiaoge.drivingassist.CarrotManFields
import com.jixiexiaoge.drivingassist.LaneInfo
import io.mockk.every
import io.mockk.mockk
import org.json.JSONObject
import org.junit.Test

abstract class NaviStreamManagerTestFixture {
    protected var now = 1000L
    protected val sources = NaviSourceTracker()
    protected val messages = mutableListOf<Triple<String, Boolean, Any?>>()
    protected val timestamps = mutableListOf<Pair<String, Long>>()
    protected val client = mockk<NaviWebSocketV2Client>()

    protected fun manager(
        mode: String = "on_change",
        laneCounts: LaneCountSource = object : LaneCountSource {
            override fun latest(): LaneCountSource.Sample? = null
        },
        signalWorld: SignalWorldSource = SignalWorldSource { null },
        filterLeft: () -> Boolean = { true },
    ): NaviStreamManager {
        every { client.isReady() } returns true
        every { client.getTargetHost() } returns "127.0.0.1"
        every { client.getSession() } returns NaviV2Session("test", 1,
            NaviV2Constants.JSON_NAMES.associate { name ->
                "json:$name" to StreamConfig("json", name, params = mapOf(
                    "delivery_mode" to mode, "interval_ms" to 1000, "stale_timeout_ms" to 2000))
            })
        every { client.sendJsonData(any(), any(), any(), any(), any()) } answers {
            messages.add(Triple(firstArg(), secondArg(), thirdArg()))
            timestamps.add(firstArg<String>() to arg<Long>(4))
            true
        }
        return NaviStreamManager(client, sources, { now }, laneCounts, signalWorld, filterLeft).also { it.start() }
    }
}

class LeftGuidanceStreamTest : NaviStreamManagerTestFixture() {
    private val forkCodes = listOf(102, 105, 112, 115, 7, 44, 17, 75, 76, 118)

    @Test
    fun `all receiver left forks are filtered on highway expressway and unknown roads only`() {
        for (road in listOf(0, 6, 10, null, 1, 8)) {
            for (code in forkCodes) {
                messages.clear()
                val manager = manager()
                sources.updateRoadCategory(road, 1, now)
                sources.update("guidance_current", "guidance_next", nowMs = now)
                manager.update(CarrotManFields(isNavigating = true, noaRouteGeneration = 1,
                    nTBTTurnType = code, nTBTDist = 200, nTBTTurnTypeNext = code, nTBTDistNext = 600))
                val allowed = road == 1 || road == 8
                assertGuidance(manager, allowed)
                now += 1001
            }
        }
    }

    private fun assertGuidance(manager: NaviStreamManager, allowed: Boolean) {
        for (stream in listOf("guidance_current", "guidance_next")) {
            assertThat(messages.last { it.first == stream }.second).isEqualTo(allowed)
            if (!allowed) {
                assertThat(manager.debugSnapshot().getJSONObject(stream).getString("reason"))
                    .isEqualTo(LeftGuidancePolicy.WITHDRAWAL_REASON)
            }
        }
    }

    @Test
    fun `enabling filter withdraws both old guides immediately retries and clears lane action copy`() {
        var filter = false
        val manager = manager(filterLeft = { filter })
        sources.updateRoadCategory(6, 1, now)
        sources.updateLaneCurrent(listOf(LaneInfo(id = "0", driveWayNumber = 0, isRecommended = true)), 1, now)
        sources.update("guidance_current", "guidance_next", "lane_current", nowMs = now)
        val fields = CarrotManFields(isNavigating = true, noaRouteGeneration = 1,
            nTBTTurnType = 102, nTBTDist = 200, nTBTTurnTypeNext = 7, nTBTDistNext = 600,
            szTBTMainText = "靠左行驶")
        manager.update(fields)
        assertThat((messages.last { it.first == "lane_current" }.third as JSONObject).getInt("turn_code"))
            .isEqualTo(102)
        var attempts = 0
        every { client.sendJsonData("guidance_current", false, null, any(), any()) } answers {
            attempts++
            attempts > 1
        }
        now += 1
        filter = true
        manager.update(fields)
        assertThat(manager.debugSnapshot().getJSONObject("guidance_current").getBoolean("present")).isTrue()
        assertThat(messages.last { it.first == "guidance_next" }.second).isFalse()
        val laneMessages = messages.filter { it.first == "lane_current" }
        assertThat(laneMessages.map { it.second }).containsExactly(true, false, true).inOrder()
        val lane = laneMessages.last().third as JSONObject
        assertThat(lane.getInt("turn_code")).isEqualTo(-1)
        assertThat(lane.has("distance_m")).isFalse()
        assertThat(lane.getInt("road_category")).isEqualTo(1)
        manager.update(fields)
        assertThat(attempts).isEqualTo(2)
        assertThat(manager.debugSnapshot().getJSONObject("guidance_current").getBoolean("present")).isFalse()
        assertThat(fields.nTBTTurnType).isEqualTo(102)
        assertThat(fields.szTBTMainText).isEqualTo("靠左行驶")
        filter = false
        manager.update(fields)
        assertThat(messages.last { it.first == "guidance_current" }.second).isTrue()
    }

    @Test
    fun `fresh guides cannot reopen left forks when road category expires or route changes`() {
        val manager = manager()
        sources.updateRoadCategory(1, 1, now)
        now += 1000
        sources.update("guidance_current", "guidance_next", nowMs = now)
        val fields = CarrotManFields(isNavigating = true, noaRouteGeneration = 1,
            nTBTTurnType = 7, nTBTDist = 200, nTBTTurnTypeNext = 102, nTBTDistNext = 600)
        manager.update(fields)
        assertThat(timestamps.last { it.first == "guidance_current" }.second).isEqualTo(1000)
        now += 1001
        sources.update("guidance_current", "guidance_next", nowMs = now)
        manager.update(fields)
        assertThat(messages.last { it.first == "guidance_current" }.second).isFalse()
        sources.updateRoadCategory(1, 1, now)
        manager.update(fields.copy(noaRouteGeneration = 2))
        assertThat(messages.last { it.first == "guidance_next" }.second).isFalse()
        manager.update(fields, amapSelected = false)
        manager.update(fields)
        assertThat(messages.last { it.first == "guidance_current" }.second).isFalse()
        now--
        manager.update(fields)
        assertThat(messages.last { it.first == "guidance_next" }.second).isFalse()
    }

    @Test
    fun `turns right exits straight and independent streams remain unchanged`() {
        val manager = manager()
        sources.updateRoadCategory(0, 1, now)
        sources.updateRoadSpeedLimit(100, 1, now)
        sources.updateNavigationSignal(1, 0, 10, 1, now)
        sources.updatePosition(NaviPositionSample(31.0, 121.0, 60.0, 90.0, now), true)
        sources.update("guidance_current", "navigation_status", nowMs = now)
        manager.update(CarrotManFields(isNavigating = true, noaRouteGeneration = 1,
            nTBTTurnType = 7, nTBTDist = 200))
        assertThat(messages.last { it.first == "guidance_current" }.second).isFalse()
        assertThat((messages.last { it.first == "navigation_status" }.third as JSONObject)
            .getBoolean("guidance_active")).isTrue()
        sources.updateRoute(AmapRouteSource(2000, 120, 3000, 1, now))
        manager.update(CarrotManFields(isNavigating = true, noaRouteGeneration = 1,
            nTBTTurnType = 7, nTBTDist = 200))
        assertThat(messages.last { it.first == "route" }.second).isTrue()
        for (stream in listOf("speed", "traffic_signal", "vehicle")) {
            assertThat(messages.last { it.first == stream }.second).isTrue()
        }
        for (code in listOf(12, 16, 13, 19, 101, 104, 6, 51)) {
            sources.update("guidance_current", nowMs = now)
            manager.update(CarrotManFields(isNavigating = true, noaRouteGeneration = 1,
                nTBTTurnType = code, nTBTDist = 200))
            val value = messages.last { it.first == "guidance_current" }.third as JSONObject
            assertThat(value.getInt("turn_type")).isEqualTo(code)
            now += 1000
        }
    }
}

class NaviStreamManagerTest : NaviStreamManagerTestFixture() {
    @Test
    fun `car 7711 phase wins without mixing stop line distance or countdown`() {
        val manager = manager(signalWorld = { SignalWorld(stopLineDistM = 45f, lightColor = 1, ageMs = 90) })
        val fields = CarrotManFields(isNavigating = true, noaRouteGeneration = 1)
        // 高德给左转绿 10 秒，车端说红：相位冲突，车端赢且不借左转倒计时
        sources.updateNavigationSignal(2, 1, 10, 1, now)
        manager.update(fields)
        val signal = messages.last { it.first == "traffic_signal" }.third as JSONObject
        assertThat(signal.has("distance_m")).isFalse()
        assertThat(timestamps.last { it.first == "traffic_signal" }.second).isEqualTo(now - 90)
        assertThat(signal.getJSONObject("lights").getJSONObject("red").getBoolean("on")).isTrue()
        assertThat(signal.getJSONObject("lights").has("left")).isFalse()
    }

    @Test
    fun `car 7711 keeps sending a lamp without navigation guidance`() {
        // 未导航也能给实景灯：车端 CAN 与导航状态无关
        val manager = manager(signalWorld = { SignalWorld(stopLineDistM = 20f, lightColor = 2, ageMs = 50) })
        manager.update(CarrotManFields(isNavigating = false))
        val signal = messages.last { it.first == "traffic_signal" }.third as JSONObject
        assertThat(signal.has("distance_m")).isFalse()
        assertThat(signal.getJSONObject("lights").getJSONObject("green").getBoolean("valid")).isTrue()
    }

    @Test
    fun `car 7711 beyond 125m falls back to amap instead of using a far phase`() {
        val manager = manager(signalWorld = { SignalWorld(stopLineDistM = 180f, lightColor = 1, ageMs = 90) })
        val fields = CarrotManFields(isNavigating = true, noaRouteGeneration = 1)
        // 无高德源 → 车端超距 → 整条流撤销，reason 说明是距离超范围
        manager.update(fields)
        assertThat(messages.last { it.first == "traffic_signal" }.second).isFalse()
        assertThat(manager.debugSnapshot().getJSONObject("traffic_signal").getString("reason"))
            .isEqualTo("car_signal_distance_out_of_range")
        // 高德有有效相位时仍可回退
        sources.updateNavigationSignal(1, 0, 10, 1, now)
        manager.update(fields)
        val signal = messages.last { it.first == "traffic_signal" }.third as JSONObject
        assertThat(signal.has("distance_m")).isFalse()
        assertThat(manager.debugSnapshot().getJSONObject("signalMerge").getString("chosen"))
            .isEqualTo("amap")
    }

    @Test
    fun `unrelated vehicle telemetry cannot keep stale guidance alive`() {
        val manager = manager()
        sources.update("guidance_current", "navigation_status", nowMs = now)
        val fields = CarrotManFields(isNavigating = true, nTBTDist = 500, nTBTTurnType = 13)
        manager.update(fields)
        now += 2001
        manager.update(fields.copy(lastUpdateTime = now, vEgoKph = 100))
        assertThat(messages.last { it.first == "guidance_current" }.second).isFalse()
    }

    @Test
    fun `same data returning after absent is sent again as present`() {
        val manager = manager()
        val fields = CarrotManFields(isNavigating = true, nTBTDist = 500, nTBTTurnType = 13)
        sources.update("guidance_current", nowMs = now)
        manager.update(fields)
        now += 2001
        manager.update(fields)
        sources.update("guidance_current", nowMs = now)
        manager.update(fields)
        assertThat(messages.filter { it.first == "guidance_current" }.map { it.second })
            .containsExactly(true, false, true).inOrder()
    }

    @Test
    fun `failed present send is retried without source value change`() {
        val manager = manager()
        every { client.sendJsonData("guidance_current", true, any(), any(), any()) } returnsMany listOf(false, true)
        sources.update("guidance_current", nowMs = now)
        val fields = CarrotManFields(isNavigating = true, nTBTDist = 500, nTBTTurnType = 13)
        manager.update(fields)
        assertThat(manager.debugSnapshot().getJSONObject("guidance_current").getBoolean("present")).isFalse()
        manager.update(fields)
        assertThat(manager.debugSnapshot().getJSONObject("guidance_current").getBoolean("present")).isTrue()
    }

    @Test
    fun `navigation termination withdraws guidance route and speed`() {
        val manager = manager()
        sources.update("guidance_current", "navigation_status", "route", "road_limit", nowMs = now)
        val fields = CarrotManFields(isNavigating = true, nTBTDist = 500, nTBTTurnType = 13, nRoadLimitSpeed = 100)
        manager.update(fields)
        sources.stopNavigation()
        manager.update(fields.copy(isNavigating = false))
        for (name in listOf("guidance_current", "route", "speed")) {
            assertThat(messages.last { it.first == name }.second).isFalse()
        }
    }

    @Test
    fun `fresh stopped map position sends zero speed and expired map falls back to phone`() {
        val manager = manager()
        sources.updatePosition(NaviPositionSample(31.2, 121.5, 0.0, 90.0, now), true)
        manager.update(CarrotManFields(nPosSpeed = 50.0, vEgoKph = 100))
        val first = messages.last { it.first == "vehicle" }.third as JSONObject
        assertThat(first.getDouble("speed_kph")).isEqualTo(0.0)
        now += 2001
        sources.updatePosition(NaviPositionSample(32.0, 120.0, 36.0, 180.0, now), false)
        manager.update(CarrotManFields(szPosRoadName = "旧地图道路"))
        val fallback = messages.last { it.first == "vehicle" }.third as JSONObject
        assertThat(fallback.getDouble("lat")).isEqualTo(32.0)
        assertThat(fallback.getDouble("speed_kph")).isEqualTo(36.0)
        assertThat(fallback.getString("road_name")).isEmpty()
    }

    @Test
    fun `fresh road limits cannot refresh stale road categories`() {
        val manager = manager()
        val fields = CarrotManFields(isNavigating = true, roadType = 0, roadcate = 10, nRoadLimitSpeed = 100)
        sources.updateRoadCategory(0, 0, now)
        manager.update(fields)
        assertThat(messages.last { it.first == "lane_current" }.second).isTrue()
        now += 2001
        sources.update("road_limit", nowMs = now)
        manager.update(fields)
        assertThat(messages.last { it.first == "lane_current" }.second).isFalse()
    }

    @Test
    fun `secondary road category is not mistaken for missing road type`() {
        val manager = manager()
        sources.updateRoadCategory(8, 0, now)
        manager.update(CarrotManFields(isNavigating = true, roadType = 8, roadcate = 6))
        val lane = messages.last { it.first == "lane_current" }.third as JSONObject
        assertThat(lane.getInt("road_category")).isEqualTo(6)
        assertThat(lane.getInt("count")).isEqualTo(0)
        assertThat(lane.getBoolean("visible")).isFalse()
    }

    @Test
    fun `debug snapshot retains complete navigation JSON instead of truncating it`() {
        val manager = manager()
        val text = "navigation detail ".repeat(20)
        sources.update("guidance_current", nowMs = now)
        manager.update(CarrotManFields(
            isNavigating = true, nTBTDist = 500, nTBTTurnType = 13, szTBTMainText = text,
        ))
        val sentValue = messages.last { it.first == "guidance_current" }.third as JSONObject
        val debugValue = manager.debugSnapshot().getJSONObject("guidance_current").getString("value")
        assertThat(debugValue.length).isGreaterThan(120)
        assertThat(debugValue).isEqualTo(sentValue.toString())
    }

    @Test
    fun `periodic mode resends unchanged current app state`() {
        val manager = manager("periodic")
        manager.update(CarrotManFields())
        now += 999
        manager.update(CarrotManFields())
        assertThat(messages.count { it.first == "app_status" }).isEqualTo(1)
        now++
        manager.update(CarrotManFields())
        assertThat(messages.count { it.first == "app_status" }).isEqualTo(2)
    }

    @Test
    fun `heartbeats retain real source receipt and new identical guides carry a new receipt`() {
        // Given
        val manager = manager("on_change_with_heartbeat")
        sources.update("guidance_current", nowMs = now)
        val fields = CarrotManFields(isNavigating = true, nTBTDist = 500, nTBTTurnType = 13)
        // When
        manager.update(fields)
        now += 1000
        manager.update(fields)
        sources.update("guidance_current", nowMs = now)
        now += 1000
        manager.update(fields)
        // Then
        assertThat(timestamps.filter { it.first == "guidance_current" }.map { it.second })
            .containsExactly(1000L, 1000L, 2000L).inOrder()
    }

    @Test
    fun `current and next times require the same route and exact guide receipt`() {
        // Given
        val manager = manager()
        val fields = CarrotManFields(isNavigating = true, nTBTDist = 500, nTBTTurnType = 13,
            nTBTDistNext = 200, nTBTTurnTypeNext = 2, noaRouteGeneration = 1)
        val extras = AmapGuideExtras(now, 1, null, null, null, null, 3, 2, null, null, null, null,
            currentSegmentSec = 80, nextSegmentSec = 15)
        sources.updateGuideExtras(extras)
        sources.update("guidance_current", "guidance_next", nowMs = now)
        // When / Then
        manager.update(fields)
        assertThat((messages.last { it.first == "guidance_current" }.third as JSONObject).getInt("time_sec"))
            .isEqualTo(80)
        assertThat((messages.last { it.first == "guidance_next" }.third as JSONObject).getInt("time_sec"))
            .isEqualTo(15)
        now += 1000
        sources.update("guidance_current", nowMs = now)
        manager.update(fields)
        assertThat((messages.last { it.first == "guidance_current" }.third as JSONObject).has("time_sec")).isFalse()
        now += 1000
        sources.updateGuideExtras(extras.copy(receivedAtMs = now, routeGeneration = 2))
        sources.update("guidance_next", nowMs = now)
        manager.update(fields)
        assertThat((messages.last { it.first == "guidance_next" }.third as JSONObject).has("time_sec")).isFalse()
    }

    @Test
    fun `route summary uses source facts and never interprets ETA differences as elapsed time`() {
        // Given
        val manager = manager()
        sources.updateRouteTotal(12_000)
        sources.updateGuideExtras(AmapGuideExtras(now, 0, null, null, null, null, null, null, null, null, null, null,
            totalTimeSec = 1800))
        sources.updateRoute(AmapRouteSource(4_000, 600, 12_000, 0, now))
        val fields = CarrotManFields(isNavigating = true, nGoPosDist = 4_000, nGoPosTime = 600)
        // When
        manager.update(fields)
        val route = messages.last { it.first == "route" }.third as JSONObject
        assertThat(route.has("moved_time_sec")).isFalse()
        assertThat(route.getInt("moved_distance_m")).isEqualTo(8_000)
        // 路况重估后全程时间小于剩余时间时不得出现负值/假进度
        sources.updateGuideExtras(AmapGuideExtras(now, 0, null, null, null, null, null, null, null, null, null, null,
            totalTimeSec = 300))
        manager.update(fields)
        assertThat((messages.last { it.first == "route" }.third as JSONObject).has("moved_time_sec")).isFalse()
    }

    @Test
    fun `road limit uses raw route bound source rather than smart speed output`() {
        // Given
        val manager = manager()
        val fields = CarrotManFields(isNavigating = true, nRoadLimitSpeed = 60, noaRouteGeneration = 1)
        sources.updateRoadSpeedLimit(100, 1, now)
        sources.update("road_limit", nowMs = now)
        // When / Then
        manager.update(fields)
        assertThat((messages.last { it.first == "speed" }.third as JSONObject).getInt("road_limit_kph"))
            .isEqualTo(100)
        now += 1000
        sources.updateRoadSpeedLimit(0, 1, now)
        manager.update(fields)
        assertThat(messages.last { it.first == "speed" }.second).isFalse()
        sources.updateRoadSpeedLimit(80, 2, now)
        manager.update(fields)
        assertThat(messages.last { it.first == "speed" }.second).isFalse()
        sources.updateRoadSpeedLimit(80, 1, now)
        now += 2001
        manager.update(fields)
        assertThat(messages.last { it.first == "speed" }.second).isFalse()
    }

    @Test
    fun `explicit source bound policy transmits sixty and expires independently of a newer raw limit`() {
        val manager = manager()
        val fields = CarrotManFields(isNavigating = true, noaRouteGeneration = 1, nRoadLimitSpeed = 120)
        sources.updateRoadSpeedLimit(40, 1, now, policyLimitKph = 60, policyReceivedAtMs = now - 500)
        manager.update(fields)
        assertThat((messages.last { it.first == "speed" }.third as JSONObject).getInt("road_limit_kph"))
            .isEqualTo(60)
        assertThat(sources.snapshot().roadSpeedLimit!!.limitKph).isEqualTo(40)
        assertThat(timestamps.last { it.first == "speed" }.second).isEqualTo(500)
        now += 1501
        manager.update(fields)
        assertThat((messages.last { it.first == "speed" }.third as JSONObject).getInt("road_limit_kph"))
            .isEqualTo(40)
        sources.updateRoadSpeedLimit(40, 2, now, policyLimitKph = 60)
        manager.update(fields)
        assertThat(messages.last { it.first == "speed" }.second).isFalse()
        sources.updateRoadSpeedLimit(40, 1, now, policyLimitKph = 60)
        sources.stopNavigation(now)
        manager.update(fields)
        assertThat(messages.last { it.first == "speed" }.second).isFalse()
    }

    @Test
    fun `composite speed uses oldest included receipt and position uses its own clock`() {
        // Given
        val manager = manager()
        sources.updateRoadSpeedLimit(100, 1, now)
        now += 500
        sources.updateSdi(AmapSdiSource(1, 500, 80, 1, now), 1, now)
        sources.updatePosition(NaviPositionSample(31.2, 121.5, 36.0, 90.0, now), true)
        // When
        manager.update(CarrotManFields(isNavigating = true, noaRouteGeneration = 1, nSdiType = 1))
        // Then
        assertThat(timestamps.last { it.first == "speed" }.second).isEqualTo(1000)
        assertThat(timestamps.last { it.first == "vehicle" }.second).isEqualTo(1500)
    }

    @Test
    fun `older included position cannot be stamped as a new speed observation`() {
        // Given
        val manager = manager()
        sources.updatePosition(NaviPositionSample(31.2, 121.5, 36.0, 90.0, 500), true)
        sources.updateRoadSpeedLimit(100, 1, now)
        // When
        manager.update(CarrotManFields(isNavigating = true, noaRouteGeneration = 1))
        // Then
        assertThat(timestamps.last { it.first == "speed" }.second).isEqualTo(500)
    }

    @Test
    fun `wire road categories use source facts without changing legacy fields or defaulting unknown to zero`() {
        val manager = manager()
        val fields = CarrotManFields(isNavigating = true, roadType = 6, roadcate = 10, noaRouteGeneration = 1)
        for (type in listOf(0, 6, 1, 8)) {
            sources.updateRoadCategory(type, 1, now)
            manager.update(fields)
            val lane = messages.last { it.first == "lane_current" }.third as JSONObject
            assertThat(lane.getInt("road_category")).isEqualTo(if (type == 0 || type == 6) 1 else 6)
            now += 1000
        }
        sources.updateRoadCategory(10, 1, now)
        manager.update(fields)
        assertThat(messages.last { it.first == "lane_current" }.second).isFalse()
        assertThat(fields.roadcate).isEqualTo(10)
        now++
        sources.updateRoadCategory(null, 1, now)
        assertThat(sources.snapshot().roadCategory).isNull()
        assertThat(sources.snapshot().updatedAt).doesNotContainKey("road_category")
        sources.updateRoadCategory(0, 1, now - 1)
        assertThat(sources.snapshot().roadCategory).isNull()
    }

    @Test
    fun `fresh lanes cannot renew expired road category and unknown mixed lane is withheld`() {
        val manager = manager("on_change_with_heartbeat")
        val fields = CarrotManFields(isNavigating = true, noaRouteGeneration = 1)
        val lanes = listOf(LaneInfo(id = "0", driveWayNumber = 0, isRecommended = true))
        sources.updateRoadCategory(6, 1, now)
        sources.updateLaneCurrent(lanes, 1, now)
        sources.update("lane_current", nowMs = now)
        manager.update(fields)
        now += 1000
        manager.update(fields)
        assertThat(messages.count { it.first == "lane_current" && it.second }).isEqualTo(1)
        now += 1001
        sources.updateLaneCurrent(lanes, 1, now)
        sources.update("lane_current", nowMs = now)
        manager.update(fields)
        assertThat(messages.last { it.first == "lane_current" }.second).isFalse()
        assertThat(manager.debugSnapshot().getJSONObject("lane_current").getString("reason"))
            .isEqualTo("unknown_road_category_receiver_compat")
    }

    @Test
    fun `lane ordering requires a complete permutation and does not fabricate available turn info or distance`() {
        val manager = manager()
        val fields = CarrotManFields(isNavigating = true, noaRouteGeneration = 1)
        // 99 / 98 都不是已知的高德车道 Back_icon：整组不可翻译时 turn_info 必须整键省略
        val lanes = listOf(
            LaneInfo(id = "99", driveWayNumber = 1, isRecommended = true),
            LaneInfo(id = "98", driveWayNumber = 0, isRecommended = false),
        )
        sources.updateRoadCategory(6, 1, now)
        sources.updateLaneCurrent(lanes, 1, now)
        sources.update("lane_current", nowMs = now)
        manager.update(fields)
        val lane = messages.last { it.first == "lane_current" }.third as JSONObject
        assertThat(lane.getInt("count")).isEqualTo(2)
        assertThat(lane.getJSONArray("lanes").getJSONObject(0).getString("direction")).isEqualTo("98")
        assertThat(lane.getBoolean("lane_play")).isTrue()
        assertThat(lane.getInt("current_lane")).isEqualTo(-1)
        for (key in listOf("available", "turn_info", "distance_m")) assertThat(lane.has(key)).isFalse()
        now += 1000
        sources.updateLaneCurrent(lanes.map { it.copy(driveWayNumber = 0) }, 1, now)
        sources.update("lane_current", nowMs = now)
        manager.update(fields)
        val unknownOrder = messages.last { it.first == "lane_current" }.third as JSONObject
        assertThat(unknownOrder.getInt("count")).isEqualTo(0)
        assertThat(unknownOrder.getBoolean("visible")).isFalse()
    }

    @Test
    fun `map lane icons translate into carrot turn codes and keep order`() {
        val manager = manager()
        val fields = CarrotManFields(isNavigating = true, noaRouteGeneration = 1, nTBTDist = 120)
        // 0=直行 1=左转 3=右转 2=直行/左转 → 51 / 12 / 13 / 12（多方向取更具体的那一个）
        val lanes = listOf(
            LaneInfo(id = "0", driveWayNumber = 0, isRecommended = false),
            LaneInfo(id = "1", driveWayNumber = 1, isRecommended = false),
            LaneInfo(id = "3", driveWayNumber = 2, isRecommended = false),
            LaneInfo(id = "2", driveWayNumber = 3, isRecommended = false),
        )
        sources.updateRoadCategory(6, 1, now)
        sources.updateLaneCurrent(lanes, 1, now)
        sources.update("lane_current", nowMs = now)
        manager.update(fields)
        val lane = messages.last { it.first == "lane_current" }.third as JSONObject
        val turnInfo = lane.getJSONArray("turn_info")
        assertThat(turnInfo.length()).isEqualTo(4)
        assertThat((0 until 4).map { turnInfo.getInt(it) })
            .containsExactly(51, 12, 13, 12).inOrder()
        assertThat(lane.getString("lane_source")).isEqualTo("map")
        assertThat(lane.has("model_count")).isFalse()
    }

    @Test
    fun `uturn lane icon maps to cp uturn code instead of straight`() {
        val manager = manager()
        val lanes = listOf(
            LaneInfo(id = "5", driveWayNumber = 0, isRecommended = false),   // 左掉头
            LaneInfo(id = "8", driveWayNumber = 1, isRecommended = false),   // 右掉头
            LaneInfo(id = "7", driveWayNumber = 2, isRecommended = false),   // 左/直/右
        )
        sources.updateRoadCategory(6, 1, now)
        sources.updateLaneCurrent(lanes, 1, now)
        sources.update("lane_current", nowMs = now)
        manager.update(CarrotManFields(isNavigating = true, noaRouteGeneration = 1))
        val turnInfo = (messages.last { it.first == "lane_current" }.third as JSONObject).getJSONArray("turn_info")
        assertThat((0 until 3).map { turnInfo.getInt(it) }).containsExactly(14, 14, 12).inOrder()
    }

    @Test
    fun `lane count and current lane come from the on-device model when map lanes are absent`() {
        var sample: LaneCountSource.Sample? =
            LaneCountSource.Sample(3, 2, now, "vlaneDay", "127.0.0.1", 1, 1)
        val manager = manager(laneCounts = object : LaneCountSource {
            override fun latest(): LaneCountSource.Sample? = sample
        })
        val fields = CarrotManFields(isNavigating = true, noaRouteGeneration = 1, nTBTDist = 80)
        // 只有道路类别、没有 13012 车道图
        sources.updateRoadCategory(6, 1, now)
        manager.update(fields)
        val lane = messages.last { it.first == "lane_current" }.third as JSONObject
        assertThat(lane.getInt("count")).isEqualTo(3)
        assertThat(lane.getInt("current_lane")).isEqualTo(2)
        assertThat(lane.getBoolean("visible")).isTrue()
        assertThat(lane.getString("lane_source")).isEqualTo("model")
        assertThat(lane.getInt("model_count")).isEqualTo(3)
        assertThat(lane.getInt("model_current_lane")).isEqualTo(2)
        // 模型不提供逐车道可用性与路口距离，这两组键必须缺席
        assertThat(lane.has("available")).isFalse()
        assertThat(lane.has("distance_m")).isFalse()
        assertThat(lane.has("turn_info")).isFalse()

        // 模型过期（超过 1.2s）→ 车道数回落成 0，不能拿最后一帧当有效值
        now += 1300
        manager.update(fields)
        val stale = messages.last { it.first == "lane_current" }.third as JSONObject
        assertThat(stale.getInt("count")).isEqualTo(0)
        assertThat(stale.getBoolean("visible")).isFalse()
        assertThat(stale.getInt("current_lane")).isEqualTo(-1)
        sample = null
    }

    @Test
    fun `map junction count never adopts a model index without verified lane association`() {
        val manager = manager(laneCounts = object : LaneCountSource {
            override fun latest(): LaneCountSource.Sample =
                LaneCountSource.Sample(3, 3, now, "vlaneDay", "127.0.0.1", 1, 1)
        })
        val lanes = listOf(
            LaneInfo(id = "0", driveWayNumber = 0, isRecommended = false),
            LaneInfo(id = "0", driveWayNumber = 1, isRecommended = false),
            LaneInfo(id = "0", driveWayNumber = 2, isRecommended = false),
            LaneInfo(id = "0", driveWayNumber = 3, isRecommended = false),
        )
        sources.updateRoadCategory(6, 1, now)
        sources.updateLaneCurrent(lanes, 1, now)
        sources.update("lane_current", nowMs = now)
        manager.update(CarrotManFields(isNavigating = true, noaRouteGeneration = 1))
        val lane = messages.last { it.first == "lane_current" }.third as JSONObject
        assertThat(lane.getInt("count")).isEqualTo(4)
        assertThat(lane.getInt("current_lane")).isEqualTo(-1)
        assertThat(lane.getString("lane_source")).isEqualTo("map+model")
        assertThat(lane.getInt("model_count")).isEqualTo(3)
        now += 1000
        sources.updateLaneCurrent(lanes.take(3), 1, now)
        sources.update("lane_current", nowMs = now)
        manager.update(CarrotManFields(isNavigating = true, noaRouteGeneration = 1))
        val sameCount = messages.last { it.first == "lane_current" }.third as JSONObject
        assertThat(sameCount.getInt("count")).isEqualTo(3)
        assertThat(sameCount.getInt("current_lane")).isEqualTo(-1)
    }

    @Test
    fun `model current lane outside the published count is withheld instead of pointing nowhere`() {
        val manager = manager(laneCounts = object : LaneCountSource {
            // 模型认为在第 5 条，但地图只有 4 条 → 不能发越界索引
            override fun latest(): LaneCountSource.Sample =
                LaneCountSource.Sample(5, 5, now, "vlaneDay", "127.0.0.1", 1, 1)
        })
        val lanes = (0 until 4).map { LaneInfo(id = "0", driveWayNumber = it, isRecommended = false) }
        sources.updateRoadCategory(6, 1, now)
        sources.updateLaneCurrent(lanes, 1, now)
        sources.update("lane_current", nowMs = now)
        manager.update(CarrotManFields(isNavigating = true, noaRouteGeneration = 1))
        val lane = messages.last { it.first == "lane_current" }.third as JSONObject
        assertThat(lane.getInt("count")).isEqualTo(4)
        assertThat(lane.getInt("current_lane")).isEqualTo(-1)
        // 诊断字段仍保留模型原值，便于现场判断是模型算错还是地图缺条
        assertThat(lane.getInt("model_current_lane")).isEqualTo(5)
    }

    @Test
    fun `lane ahead stays withdrawn until a next junction lane source exists`() {
        val manager = manager()
        sources.updateRoadCategory(6, 1, now)
        manager.update(CarrotManFields(isNavigating = true, noaRouteGeneration = 1))
        assertThat(messages.last { it.first == "lane_ahead" }.second).isFalse()
        assertThat(manager.debugSnapshot().getJSONObject("lane_ahead").getString("reason"))
            .isEqualTo("next_junction_lane_map_absent")
    }

    @Test
    fun `model lanes from an old target route or unbound session are withheld`() {
        var sample = LaneCountSource.Sample(3, 2, now, "model", "127.0.0.1", 1, 1)
        val manager = manager(laneCounts = object : LaneCountSource {
            override fun latest() = sample
        })
        val fields = CarrotManFields(isNavigating = true, noaRouteGeneration = 1)
        sources.updateRoadCategory(6, 1, now)
        for (invalid in listOf(
            sample.copy(targetHost = "127.0.0.2"),
            sample.copy(routeGeneration = 0),
            sample.copy(sessionId = 0),
        )) {
            sample = invalid
            manager.update(fields)
            val lane = messages.last { it.first == "lane_current" }.third as JSONObject
            assertThat(lane.getInt("current_lane")).isEqualTo(-1)
            assertThat(lane.getInt("count")).isEqualTo(0)
        }
    }

    @Test
    fun `unchanged car receipt does not get renewed and yellow withdraws a previous green`() {
        var age = 100L
        var color = 2
        val manager = manager(signalWorld = { SignalWorld(40f, color, age) })
        manager.update(CarrotManFields())
        val receipt = timestamps.last { it.first == "traffic_signal" }.second
        now += 1000
        age += 1000
        manager.update(CarrotManFields())
        assertThat(timestamps.last { it.first == "traffic_signal" }.second).isEqualTo(receipt)
        color = 3
        manager.update(CarrotManFields())
        assertThat(messages.last { it.first == "traffic_signal" }.second).isFalse()
    }

    @Test
    fun `signal only emits verified timed phase and clears on missing countdown or route reset`() {
        val manager = manager()
        val fields = CarrotManFields(isNavigating = true, noaRouteGeneration = 1, nTBTDist = 321)
        sources.updateNavigationSignal(2, 1, 8, 1, now)
        manager.update(fields)
        val signal = messages.last { it.first == "traffic_signal" }.third as JSONObject
        assertThat(signal.getJSONObject("lights").has("left")).isTrue()
        assertThat(signal.getJSONObject("lights").has("green")).isFalse()
        assertThat(signal.has("distance_m")).isFalse()
        now += 1000
        manager.update(fields)
        assertThat((messages.last { it.first == "traffic_signal" }.third as JSONObject)
            .getJSONObject("ui_counter").getInt("remain_sec")).isEqualTo(7)
        assertThat(timestamps.last { it.first == "traffic_signal" }.second).isEqualTo(1000)
        now++
        sources.updateNavigationSignal(2, 1, null, 1, now)
        manager.update(fields)
        assertThat(messages.last { it.first == "traffic_signal" }.second).isFalse()
        sources.updateNavigationSignal(1, 1, 8, 1, now - 1)
        assertThat(sources.snapshot().navigationSignal).isNull()
        now++
        sources.updateNavigationSignal(1, 1, 8, 1, now)
        manager.update(fields)
        sources.resetGuideDisplay(now + 1)
        manager.update(fields)
        assertThat(messages.last { it.first == "traffic_signal" }.second).isFalse()
    }

    @Test
    fun `route mismatch source switch and clock rollback withdraw category and timed signals`() {
        val manager = manager()
        val fields = CarrotManFields(isNavigating = true, noaRouteGeneration = 1)
        sources.updateRoadCategory(6, 1, now)
        sources.updateNavigationSignal(1, 1, 10, 1, now)
        manager.update(fields)
        manager.update(fields.copy(noaRouteGeneration = 2))
        assertThat(messages.last { it.first == "lane_current" }.second).isFalse()
        assertThat(messages.last { it.first == "traffic_signal" }.second).isFalse()
        manager.update(fields, amapSelected = false)
        manager.update(fields, amapSelected = true)
        assertThat(sources.snapshot().roadCategory).isNull()
        assertThat(sources.snapshot().navigationSignal).isNull()
        now += 1000
        sources.updateRoadCategory(6, 1, now)
        sources.updateNavigationSignal(1, 1, 10, 1, now)
        manager.update(fields)
        now -= 500
        manager.update(fields)
        assertThat(messages.last { it.first == "lane_current" }.second).isFalse()
        assertThat(messages.last { it.first == "traffic_signal" }.second).isFalse()
        now += 1000
        manager.update(fields)
        assertThat(sources.snapshot().roadCategory).isNull()
        assertThat(sources.snapshot().navigationSignal).isNull()
    }

    @Test
    fun `partial speed expiry withdraws immediately inside throttle and retries failed subset enqueue`() {
        val manager = manager()
        val fields = CarrotManFields(isNavigating = true, noaRouteGeneration = 1, nSdiType = 1)
        sources.updateRoadSpeedLimit(100, 1, now - 1500)
        sources.updateSdi(AmapSdiSource(1, 500, 80, 1, now), 1, now)
        manager.update(fields)
        now += 501
        every { client.sendJsonData("speed", false, any(), any(), any()) } returnsMany listOf(false, true)
        manager.update(fields)
        assertThat(manager.debugSnapshot().getJSONObject("speed").getBoolean("present")).isTrue()
        every { client.sendJsonData("speed", true, any(), any(), any()) } returnsMany listOf(false, true)
        manager.update(fields)
        assertThat(manager.debugSnapshot().getJSONObject("speed").getBoolean("present")).isFalse()
        manager.update(fields)
        val subset = JSONObject(manager.debugSnapshot().getJSONObject("speed").getString("value"))
        assertThat(subset.has("road_limit_kph")).isFalse()
        assertThat(subset.has("sdi")).isTrue()
    }

    @Test
    fun `section without remaining distance is withheld instead of active with zero`() {
        val manager = manager()
        val fields = CarrotManFields(isNavigating = true, noaRouteGeneration = 1,
            nSdiBlockType = 2, nSdiBlockSpeed = 80)
        sources.updateRoadSpeedLimit(100, 1, now)
        sources.updateSection(400.0, 70.0, 0.5, now, speedLimitKph = 80, routeGeneration = 1)
        manager.update(fields)
        now += 100
        // 区间源被清空：block_type 仍是 2，但没有剩余距离了
        sources.updateSection(null, null, null, now, routeGeneration = 1)
        manager.update(fields)
        assertThat(messages.filter { it.first == "speed" }.map { it.second })
            .containsExactly(true, false, true).inOrder()
        val subset = messages.last { it.first == "speed" }.third as JSONObject
        assertThat(subset.getInt("road_limit_kph")).isEqualTo(100)
        // 缺剩余距离时必须整块撤销 section：发 active=true + 缺失 remaining 会被
        // CP 解析成 remaining=0，等于告诉对方「区间已经跑完」，比不发更糟。
        assertThat(subset.has("section")).isFalse()
        assertThat(timestamps.last { it.first == "speed" }.second).isEqualTo(1000)
    }

    @Test
    fun `source switch publishes idle immediately even inside navigation status throttle`() {
        val manager = manager()
        val fields = CarrotManFields(isNavigating = true, nTBTDist = 500, nTBTTurnType = 13)
        sources.update("guidance_current", "navigation_status", nowMs = now)
        manager.update(fields)
        assertThat((messages.last { it.first == "navigation_status" }.third as JSONObject)
            .getBoolean("guidance_active")).isTrue()
        now++
        manager.update(fields, amapSelected = false)
        val status = messages.last { it.first == "navigation_status" }.third as JSONObject
        assertThat(status.getString("mode")).isEqualTo("idle")
        assertThat(status.getBoolean("guidance_active")).isFalse()
    }
}
