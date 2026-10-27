package com.jixiexiaoge.drivingassist

import android.content.Intent
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import com.google.common.truth.Truth.assertThat
import com.jixiexiaoge.drivingassist.navigation.NaviSourceTracker
import com.jixiexiaoge.drivingassist.navigation.NaviStreamManager
import com.jixiexiaoge.drivingassist.navigation.NaviWebSocketV2Client
import com.jixiexiaoge.drivingassist.navigation.NaviV2Session
import com.jixiexiaoge.drivingassist.navigation.StreamConfig
import com.jixiexiaoge.drivingassist.navigation.buildAmapNavigationDisplay
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.json.JSONObject

class AmapSpeedLimitBroadcastTest {
    @Test
    fun `unsupported receiver maneuver keeps independent local AMap labels and graphics`() {
        val fields = mutableStateOf(CarrotManFields())
        val sources = NaviSourceTracker()
        val handler = AmapBroadcastHandlers(fields, naviSources = sources, nowMs = { 1000L })
        handler.handleGuideInfo(broadcast(mapOf("ICON" to 4, "SEG_REMAIN_DIS" to 300)))
        assertThat(fields.value.nTBTTurnType).isEqualTo(-1)
        val display = buildAmapNavigationDisplay(fields.value, sources.snapshot(), "AMAP", 1000)
        assertThat(display.current!!.label).isEqualTo("左前方")
        assertThat(display.current!!.maneuverIcon).isEqualTo(4)
    }

    @Test
    fun `route and road name replace missing source fields instead of using shared values`() {
        var now = 1000L
        val fields = mutableStateOf(CarrotManFields())
        val sources = NaviSourceTracker()
        val handler = AmapBroadcastHandlers(fields, naviSources = sources, nowMs = { now })
        handler.handleGuideInfo(broadcast(mapOf("CUR_ROAD_NAME" to "Current road",
            "CAR_LATITUDE" to 30.0, "CAR_LONGITUDE" to 120.0,
            "ROUTE_REMAIN_DIS" to 1000, "ROUTE_REMAIN_TIME" to 100)))
        assertThat(sources.snapshot().mapPosition!!.roadName).isEqualTo("Current road")
        assertThat(sources.snapshot().route!!.remainingTimeSec).isEqualTo(100)
        now++
        handler.handleGuideInfo(broadcast(mapOf("CAR_LATITUDE" to 30.0, "CAR_LONGITUDE" to 120.0,
            "ROUTE_REMAIN_DIS" to 900)))
        assertThat(fields.value.szPosRoadName).isEqualTo("Current road")
        assertThat(sources.snapshot().mapPosition!!.roadName).isEmpty()
        assertThat(sources.snapshot().route!!.remainingTimeSec).isNull()
        now++
        handler.handleGuideInfo(broadcast(mapOf("ROUTE_REMAIN_DIS" to "invalid", "ROUTE_REMAIN_TIME" to -1)))
        assertThat(sources.snapshot().route!!.remainingDistanceM).isNull()
        assertThat(sources.snapshot().route!!.remainingTimeSec).isNull()
    }

    @Test
    fun `partial camera update cannot reuse old speed or interval block fields`() {
        var now = 1000L
        val fields = mutableStateOf(CarrotManFields())
        val sources = NaviSourceTracker()
        val handler = AmapBroadcastHandlers(fields, naviSources = sources, nowMs = { now })
        handler.handleGuideInfo(broadcast(mapOf("ROAD_TYPE" to 0, "LIMITED_SPEED" to 120,
            "CAMERA_TYPE" to 8, "CAMERA_DIST" to 500, "CAMERA_SPEED" to 80,
            "INTERVAL_DISTANCE" to 5000)))
        assertThat(sources.snapshot().sdi!!.speedLimitKph).isEqualTo(80)
        assertThat(sources.snapshot().sdi!!.blockType).isNull()
        now++
        handler.handleSpeedLimit(broadcast(mapOf("LIMITED_SPEED" to 80, "END_DISTANCE" to 300,
            "INTERVAL_DISTANCE" to 5000)))
        assertThat(sources.snapshot().section!!.speedLimitKph).isEqualTo(80)
        assertThat(sources.snapshot().sdi!!.distanceM).isEqualTo(300)
        now++
        handler.handleCameraInfo(broadcast(mapOf("CAMERA_TYPE" to 4, "CAMERA_DISTANCE" to 800,
            "CAMERA_SPEED_LIMIT" to 60)))
        assertThat(fields.value.nSdiBlockType).isEqualTo(2)
        assertThat(sources.snapshot().sdi!!.speedLimitKph).isEqualTo(60)
        assertThat(sources.snapshot().sdi!!.blockType).isNull()
        assertThat(sources.snapshot().section).isNull()
        now++
        handler.handleGuideInfo(broadcast(mapOf("CAMERA_TYPE" to 4, "CAMERA_DIST" to 700)))
        assertThat(sources.snapshot().sdi).isNull()
    }

    @Test
    fun `partial section cannot borrow old limit or turn total length into remaining distance`() {
        var now = 1000L
        val fields = mutableStateOf(CarrotManFields(isNavigating = true))
        val sources = NaviSourceTracker()
        val handler = AmapBroadcastHandlers(fields, naviSources = sources, nowMs = { now })
        handler.handleSpeedLimit(broadcast(mapOf("LIMITED_SPEED" to 100)))
        now++
        handler.handleSpeedLimit(broadcast(mapOf("LIMITED_SPEED" to "80", "END_DISTANCE" to "300",
            "INTERVAL_DISTANCE" to "5000", "AVERAGE_SPEED" to 0)))
        assertThat(sources.snapshot().section!!.remainingM).isEqualTo(300.0)
        assertThat(sources.snapshot().section!!.averageKph).isEqualTo(0.0)
        assertThat(sources.snapshot().sdi!!.toJson().getInt("block_distance_m")).isEqualTo(300)
        now++
        handler.handleSpeedLimit(broadcast(mapOf("LIMITED_SPEED" to 80, "INTERVAL_DISTANCE" to 5000)))
        assertThat(sources.snapshot().section).isNull()
        assertThat(sources.snapshot().sdi).isNull()
        now++
        handler.handleSpeedLimit(broadcast(mapOf("END_DISTANCE" to 200)))
        assertThat(sources.snapshot().section).isNull()
        assertThat(sources.snapshot().sdi).isNull()
        now++
        handler.handleSpeedLimit(broadcast(mapOf("END_DISTANCE" to "invalid", "LIMITED_SPEED" to 60)))
        assertThat(sources.snapshot().roadSpeedLimit!!.limitKph).isEqualTo(100)
        assertThat(fields.value.nRoadLimitSpeed).isEqualTo(100)
    }

    @Test
    fun `camera receipts expire independently and section end clears control facts`() {
        var now = 1000L
        val fields = mutableStateOf(CarrotManFields())
        val sources = NaviSourceTracker()
        val handler = AmapBroadcastHandlers(fields, naviSources = sources, nowMs = { now })
        handler.handleGuideInfo(broadcast(mapOf("CAMERA_TYPE" to 4, "CAMERA_DIST" to 500, "CAMERA_SPEED" to 60)))
        now++
        handler.handleGuideInfo(broadcast(mapOf("ROAD_TYPE" to 0, "LIMITED_SPEED" to 120)))
        assertThat(sources.snapshot().sdi!!.receivedAtMs).isEqualTo(1000)
        now++
        handler.handleSdiPlusInfo(broadcast(mapOf("SDI_PLUS_TYPE" to 22, "SDI_PLUS_DISTANCE" to 100)))
        assertThat(sources.snapshot().secondarySdi!!.speedLimitKph).isNull()
        now++
        handler.handleSdiPlusInfo(broadcast(mapOf("SDI_PLUS_DISTANCE" to 90)))
        assertThat(sources.snapshot().secondarySdi).isNull()
        handler.handleSpeedLimit(broadcast(mapOf("LIMITED_SPEED" to 80, "END_DISTANCE" to 300)))
        now++
        handler.handleGuideInfo(broadcast(mapOf("CAMERA_TYPE" to 9, "CAMERA_DIST" to 100, "CAMERA_SPEED" to 80)))
        assertThat(sources.snapshot().section).isNull()
        assertThat(sources.snapshot().sdi).isNull()
    }

    @Test
    fun `route light counts raw ETA and second facility clear on missing invalid or expired broadcasts`() {
        // Given
        val fields = mutableStateOf(CarrotManFields())
        val sources = NaviSourceTracker()
        val handler = AmapBroadcastHandlers(fields, naviSources = sources)
        // When
        handler.handleGuideInfo(broadcast(mapOf("TRAFFIC_LIGHT_NUM" to 8, "routeRemainTrafficLightNum" to 0,
            "ETA_TEXT" to "15:30到达", "SAPA_NAME" to "服务区A", "SAPA_DIST_AUTO" to "2公里",
            "NEXT_SAPA_NAME" to "服务区B", "NEXT_SAPA_DIST_AUTO" to "8公里", "NEXT_SAPA_TYPE" to 3)))
        // Then
        val display = buildAmapNavigationDisplay(fields.value, sources.snapshot(), "AMAP", System.currentTimeMillis())
        assertThat(display.guideDetails!!.totalTrafficLights).isEqualTo(8)
        assertThat(display.guideDetails!!.remainingTrafficLights).isEqualTo(0)
        assertThat(display.guideDetails!!.rawEta).isEqualTo("15:30到达")
        assertThat(display.guideDetails!!.nextServiceName).isEqualTo("服务区B")
        assertThat(display.guideDetails!!.nextServiceDistanceText).isEqualTo("8公里")
        assertThat(display.service).isNull() // A formatted text is not a numeric meter value.
        assertThat(buildAmapNavigationDisplay(fields.value, sources.snapshot(), "AMAP",
            display.guideDetails!!.receivedAtMs + 10001).guideDetails).isNull()
        handler.handleGuideInfo(broadcast(mapOf("TRAFFIC_LIGHT_NUM" to "bad", "routeRemainTrafficLightNum" to -1)))
        assertThat(sources.snapshot().guideExtras!!.totalTrafficLights).isNull()
        assertThat(sources.snapshot().guideExtras!!.rawEta).isNull()
        assertThat(sources.snapshot().guideExtras!!.nextServiceName).isNull()
        handler.handleGuideInfo(broadcast(mapOf("TRAFFIC_LIGHT_NUM" to 2, "routeRemainTrafficLightNum" to 3)))
        assertThat(sources.snapshot().guideExtras!!.remainingTrafficLights).isNull()
        handler.handleGuideInfo(broadcast(emptyMap()))
        assertThat(sources.snapshot().guideExtras!!.totalTrafficLights).isNull()
    }
    @Test
    fun `reported road classification is independent of legacy defaults and cleared by missing broadcast`() {
        // Given
        val fields = mutableStateOf(CarrotManFields())
        val sources = NaviSourceTracker()
        val handler = AmapBroadcastHandlers(fields, naviSources = sources)
        // When / Then
        handler.handleGuideInfo(broadcast(mapOf("ROAD_TYPE" to 6)))
        assertThat(buildAmapNavigationDisplay(fields.value, sources.snapshot(), "AMAP",
            System.currentTimeMillis()).reportedRoadType).isEqualTo(6)
        handler.handleGuideInfo(broadcast(emptyMap()))
        assertThat(fields.value.roadType).isEqualTo(6)
        assertThat(buildAmapNavigationDisplay(fields.value, sources.snapshot(), "AMAP",
            System.currentTimeMillis()).reportedRoadType).isNull()
        handler.handleGuideInfo(broadcast(mapOf("ROAD_TYPE" to 999)))
        assertThat(sources.snapshot().guideExtras!!.reportedRoadType).isNull()
        verify(exactly = 0) { Log.e(any(), any(), any()) }
    }
    @Before
    fun mockAndroidLogging() {
        mockkStatic(Log::class)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
    }

    @After
    fun restoreAndroidLogging() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `guide records original limit before ramp correction and clears it when missing`() {
        // Given
        val fields = mutableStateOf(CarrotManFields())
        val sources = NaviSourceTracker()
        val handler = AmapBroadcastHandlers(fields, naviSources = sources)
        // When
        handler.handleGuideInfo(broadcast(mapOf("LIMITED_SPEED" to 40, "ROAD_TYPE" to 6)))
        // Then
        assertThat(fields.value.nRoadLimitSpeed).isEqualTo(60)
        assertThat(buildAmapNavigationDisplay(fields.value, sources.snapshot(), "AMAP",
            System.currentTimeMillis()).roadLimitKph).isEqualTo(40)
        handler.handleGuideInfo(broadcast(emptyMap()))
        assertThat(fields.value.nRoadLimitSpeed).isEqualTo(60)
        assertThat(sources.snapshot().roadSpeedLimit).isNull()
        verify(exactly = 0) { Log.e(any(), any(), any()) }
    }

    @Test
    fun `standalone road limit reads numeric strings and section limit cannot overwrite road limit`() {
        // Given
        val fields = mutableStateOf(CarrotManFields(isNavigating = true, roadType = 6))
        val sources = NaviSourceTracker()
        val handler = AmapBroadcastHandlers(fields, naviSources = sources)
        // When
        handler.handleGuideInfo(broadcast(mapOf("ROAD_TYPE" to 6)))
        handler.handleSpeedLimit(broadcast(mapOf("LIMITED_SPEED" to "40")))
        // Then
        assertThat(fields.value.nRoadLimitSpeed).isEqualTo(60)
        assertThat(sources.snapshot().roadSpeedLimit!!.limitKph).isEqualTo(40)
        handler.handleSpeedLimit(broadcast(mapOf("LIMITED_SPEED" to 80, "END_DISTANCE" to 500)))
        assertThat(sources.snapshot().roadSpeedLimit!!.limitKph).isEqualTo(40)
        assertThat(fields.value.nSdiBlockSpeed).isEqualTo(80)
        handler.handleSpeedLimit(broadcast(emptyMap()))
        assertThat(sources.snapshot().roadSpeedLimit).isNull()
        verify(exactly = 0) { Log.e(any(), any(), any()) }
    }

    @Test
    fun `smart ramp policy is sent on existing navigation stream without rewriting raw limit or issuing commands`() {
        val values = mutableMapOf<String, Boolean>()
        val context = speedContext(values)
        val fields = mutableStateOf(CarrotManFields())
        val sources = NaviSourceTracker()
        var now = 1000L
        val network = mockk<NetworkManager>(relaxed = true)
        val handler = AmapBroadcastHandlers(fields, networkManager = network, context = context,
            naviSources = sources, nowMs = { now })
        val client = mockk<NaviWebSocketV2Client>(relaxed = true)
        every { client.isReady() } returns true
        every { client.getSession() } returns NaviV2Session("test", 1, mapOf(
            "json:speed" to StreamConfig("json", "speed", params = mapOf(
                "delivery_mode" to "on_change", "interval_ms" to 0, "stale_timeout_ms" to 10_000))
        ))
        val sent = mutableListOf<Pair<Boolean, Any?>>()
        every { client.sendJsonData(any(), any(), any(), any(), any()) } answers {
            if (firstArg<String>() == "speed") sent.add(secondArg<Boolean>() to thirdArg())
            true
        }
        val manager = NaviStreamManager(client, sources, { now }).also { it.start() }
        for (roadType in listOf(0, 6)) {
            now++
            handler.handleGuideInfo(broadcast(mapOf("ROAD_TYPE" to roadType, "LIMITED_SPEED" to 40)))
            manager.update(fields.value)
            assertThat((sent.last().second as JSONObject).getInt("road_limit_kph")).isEqualTo(60)
            assertThat(sources.snapshot().roadSpeedLimit!!.limitKph).isEqualTo(40)
            assertThat(buildAmapNavigationDisplay(fields.value, sources.snapshot(), "AMAP", now).roadLimitKph)
                .isEqualTo(40)
        }
        values[SmartSpeedLimiter.KEY_ENABLED] = false
        now++
        handler.handleGuideInfo(broadcast(mapOf("ROAD_TYPE" to 6, "LIMITED_SPEED" to 40)))
        manager.update(fields.value)
        assertThat(fields.value.nRoadLimitSpeed).isEqualTo(40)
        assertThat((sent.last().second as JSONObject).getInt("road_limit_kph")).isEqualTo(40)
        values[SmartSpeedLimiter.KEY_ENABLED] = true
        now++
        handler.handleSpeedLimit(broadcast(mapOf("LIMITED_SPEED" to 40)))
        manager.update(fields.value)
        assertThat((sent.last().second as JSONObject).getInt("road_limit_kph")).isEqualTo(60)
        values[SmartSpeedLimiter.KEY_RAMP_PROTECTION] = false
        now++
        handler.handleSpeedLimit(broadcast(mapOf("LIMITED_SPEED" to 40)))
        manager.update(fields.value)
        assertThat((sent.last().second as JSONObject).getInt("road_limit_kph")).isEqualTo(40)
        values[SmartSpeedLimiter.KEY_RAMP_PROTECTION] = true
        now += 10_001
        handler.handleSpeedLimit(broadcast(mapOf("LIMITED_SPEED" to 40)))
        manager.update(fields.value)
        assertThat((sent.last().second as JSONObject).getInt("road_limit_kph")).isEqualTo(40)
        verify(exactly = 0) { network.sendControlCommand(any(), any()) }
        verify(exactly = 0) { Log.e(any(), any(), any()) }
    }

    @Test
    fun `late mainline limit survives guide distance reset and standalone limit updates`() {
        var now = 1000L
        val fields = mutableStateOf(CarrotManFields())
        val sources = NaviSourceTracker()
        val network = mockk<NetworkManager>(relaxed = true)
        val handler = AmapBroadcastHandlers(fields, networkManager = network,
            context = speedContext(mapOf(SmartSpeedLimiter.KEY_CURVE_HOLD_ENABLED to false)),
            naviSources = sources, nowMs = { now }, monotonicMs = { now })
        handler.handleGuideInfo(broadcast(mapOf("ROAD_TYPE" to 6, "LIMITED_SPEED" to 40,
            "CUR_ROAD_NAME" to "连接匝道", "CUR_SEG_NUM" to 1, "SEG_REMAIN_DIS" to 50)))
        assertThat(fields.value.nRoadLimitSpeed).isEqualTo(60)
        now = 2000
        handler.handleGuideInfo(broadcast(mapOf("ROAD_TYPE" to 6, "LIMITED_SPEED" to 100,
            "CUR_ROAD_NAME" to "主路", "CUR_SEG_NUM" to 2, "SEG_REMAIN_DIS" to 5000)))
        assertThat(fields.value.nRoadLimitSpeed).isEqualTo(60)
        now = 5000
        handler.handleSpeedLimit(broadcast(mapOf("LIMITED_SPEED" to 100)))
        assertThat(fields.value.nRoadLimitSpeed).isEqualTo(70)
        now = 8000
        handler.handleSpeedLimit(broadcast(mapOf("LIMITED_SPEED" to 100)))
        assertThat(fields.value.nRoadLimitSpeed).isEqualTo(80)
        assertThat(sources.snapshot().roadSpeedLimit!!.limitKph).isEqualTo(100)
        assertThat(sources.snapshot().roadSpeedLimit!!.policyLimitKph).isEqualTo(80)
        verify(exactly = 0) { network.sendControlCommand(any(), any()) }
    }

    private fun speedContext(values: Map<String, Boolean>): Context {
        val prefs = mockk<SharedPreferences>(relaxed = true)
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { prefs.getBoolean(any(), any()) } answers { values[firstArg<String>()] ?: secondArg() }
        every { prefs.getString(any(), any()) } answers { secondArg() }
        every { prefs.edit() } returns editor
        every { editor.putInt(any(), any()) } returns editor
        every { editor.putString(any(), any()) } returns editor
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        every { context.getSharedPreferences(any(), any()) } returns prefs
        return context
    }

    @Test
    fun `wire category snapshot clears missing and rejects fractional overflowing and old guide receipts`() {
        val fields = mutableStateOf(CarrotManFields())
        val sources = NaviSourceTracker()
        var now = 1000L
        val handler = AmapBroadcastHandlers(fields, naviSources = sources, nowMs = { now })
        handler.handleGuideInfo(broadcast(mapOf("ROAD_TYPE" to "6")))
        assertThat(sources.snapshot().roadCategory!!.carrotCategory()).isEqualTo(1)
        for (value in listOf(null, 6.5, 4_294_967_296L, "invalid")) {
            now++
            handler.handleGuideInfo(broadcast(value?.let { mapOf("ROAD_TYPE" to it) } ?: emptyMap()))
            assertThat(sources.snapshot().roadCategory).isNull()
            assertThat(sources.snapshot().updatedAt).doesNotContainKey("road_category")
        }
        now--
        handler.handleGuideInfo(broadcast(mapOf("ROAD_TYPE" to 0)))
        assertThat(sources.snapshot().roadCategory).isNull()
    }

    @Suppress("DEPRECATION")
    private fun broadcast(values: Map<String, Any>): Intent {
        val bundle = mockk<Bundle>()
        every { bundle.get(any()) } answers { values[firstArg<String>()] }
        every { bundle.containsKey(any()) } answers { values.containsKey(firstArg<String>()) }
        val intent = mockk<Intent>(relaxed = true)
        every { intent.extras } returns bundle
        every { intent.hasExtra(any()) } answers { values.containsKey(firstArg<String>()) }
        every { intent.getStringExtra(any()) } answers { values[firstArg<String>()] as? String }
        every { intent.getIntExtra(any(), any()) } answers {
            (values[firstArg<String>()] as? Number)?.toInt() ?: secondArg<Int>()
        }
        return intent
    }

    @Test
    fun `guide exposes route totals and action times from actual keys then clears missing additions`() {
        // Given
        val fields = mutableStateOf(CarrotManFields())
        val sources = NaviSourceTracker()
        val handler = AmapBroadcastHandlers(fields, naviSources = sources)
        // When
        handler.handleGuideInfo(broadcast(mapOf(
            "ROUTE_ALL_DIS" to "20000", "ROUTE_REMAIN_DIS" to 12000,
            "ROUTE_ALL_TIME" to "1800", "ROUTE_REMAIN_TIME" to 900,
            "ICON" to 3, "SEG_REMAIN_DIS" to 500, "SEG_REMAIN_TIME" to "45",
            "NEXT_NEXT_TURN_ICON" to 2, "NEXT_SEG_REMAIN_DIS" to 200, "NEXT_SEG_REMAIN_TIME" to 90,
        )))
        // Then
        val display = buildAmapNavigationDisplay(fields.value, sources.snapshot(), "AMAP", System.currentTimeMillis())
        assertThat(display.totalDistanceM).isEqualTo(20000)
        assertThat(display.traveledDistanceM).isEqualTo(8000)
        assertThat(display.totalTimeSec).isEqualTo(1800)
        assertThat(display.arrivalAtMs).isEqualTo(sources.snapshot().guideExtras!!.receivedAtMs + 900000)
        assertThat(display.current!!.remainingSec).isEqualTo(45)
        assertThat(display.next!!.remainingSec).isEqualTo(90)
        handler.handleGuideInfo(broadcast(emptyMap()))
        val missing = buildAmapNavigationDisplay(fields.value, sources.snapshot(), "AMAP", System.currentTimeMillis())
        assertThat(missing.arrivalAtMs).isNull()
        assertThat(missing.totalDistanceM).isNull()
        assertThat(missing.totalTimeSec).isNull()
        assertThat(sources.snapshot().guideExtras!!.currentSegmentSec).isNull()
        verify(exactly = 0) { Log.e(any(), any(), any()) }
    }

    @Test
    fun `reported zero segment time is valid while malformed or negative time is unknown`() {
        // Given
        val fields = mutableStateOf(CarrotManFields())
        val sources = NaviSourceTracker()
        val handler = AmapBroadcastHandlers(fields, naviSources = sources)
        // When
        handler.handleGuideInfo(broadcast(mapOf("SEG_REMAIN_TIME" to 0, "NEXT_SEG_REMAIN_TIME" to "bad")))
        // Then
        assertThat(sources.snapshot().guideExtras!!.currentSegmentSec).isEqualTo(0)
        assertThat(sources.snapshot().guideExtras!!.nextSegmentSec).isNull()
        handler.handleGuideInfo(broadcast(mapOf("SEG_REMAIN_TIME" to "bad", "NEXT_SEG_REMAIN_TIME" to -1)))
        assertThat(sources.snapshot().guideExtras!!.currentSegmentSec).isNull()
        assertThat(sources.snapshot().guideExtras!!.nextSegmentSec).isNull()
    }
}
