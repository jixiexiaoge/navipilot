package com.jixiexiaoge.drivingassist

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import com.google.common.truth.Truth.assertThat
import com.jixiexiaoge.drivingassist.navigation.AmapCruiseLamp
import com.jixiexiaoge.drivingassist.navigation.AmapDisplaySignal
import com.jixiexiaoge.drivingassist.navigation.NaviSourceTracker
import com.jixiexiaoge.drivingassist.navigation.buildAmapNavigationDisplay
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test

class AmapCruiseBroadcastTest {
    private val fields = mutableStateOf(CarrotManFields())
    private val sources = NaviSourceTracker()
    private var selected = true
    private var time = 10_000L
    private val handler = AmapBroadcastHandlers(
        fields, naviSources = sources, isAmapSelected = { selected }, nowMs = { time },
    )
    private val lights = """[{"status":0,"dir":1,"countdown":10},{"status":1,"dir":2,"countdown":12}]"""

    @Before
    fun mockLogging() {
        mockkStatic(Log::class)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
    }

    @After
    fun restoreLogging() = unmockkStatic(Log::class)

    @Test
    fun `display keeps verified green direction separate from wire and revokes malformed input`() {
        fields.value = fields.value.copy(isNavigating = true, noaRouteGeneration = 1)
        handler.handleTrafficLightInfo(broadcast(mapOf(
            "trafficLightStatus" to 2, "dir" to 4, "greenLightLastSecond" to 8,
        )))
        assertThat(buildAmapNavigationDisplay(fields.value, sources.snapshot(), "AMAP", time).trafficSignal)
            .isEqualTo(AmapDisplaySignal(2, 4, 8))
        assertThat(sources.snapshot().navigationSignal).isNull()
        val broken = broadcast(emptyMap())
        every { broken.hasExtra("trafficLightStatus") } throws IllegalArgumentException("Malformed signal")
        time++
        handler.handleTrafficLightInfo(broken)
        assertThat(buildAmapNavigationDisplay(fields.value, sources.snapshot(), "AMAP", time).trafficSignal)
            .isNull()
        assertThat(fields.value.trafficLightState).isEqualTo(2)
        verify(exactly = 1) { Log.e(any(), any(), any()) }
    }

    @Test
    fun `navigation signal source requires explicit direction integer status and real positive countdown`() {
        fields.value = fields.value.copy(isNavigating = true, noaRouteGeneration = 1)
        handler.handleTrafficLightInfo(broadcast(mapOf(
            "trafficLightStatus" to "2", "dir" to "1", "greenLightLastSecond" to "8",
        )))
        val accepted = sources.snapshot().navigationSignal!!
        assertThat(accepted.receivedAtMs).isEqualTo(time)
        assertThat(accepted.routeGeneration).isEqualTo(1)
        assertThat(accepted.toSignal(time)!!.leftOn).isTrue()
        time++
        handler.handleTrafficLightInfo(broadcast(mapOf("lightsData" to lights)))
        assertThat(sources.snapshot().navigationSignal).isEqualTo(accepted)
        for (values in listOf(
            mapOf("trafficLightStatus" to 2, "greenLightLastSecond" to 8),
            mapOf("trafficLightStatus" to 2, "dir" to 1),
            mapOf("trafficLightStatus" to 2, "dir" to 1, "greenLightLastSecond" to 0),
            mapOf("trafficLightStatus" to 2.5, "dir" to 1, "greenLightLastSecond" to 8),
            mapOf("trafficLightStatus" to 2, "dir" to 3, "greenLightLastSecond" to 8),
        )) {
            time++
            handler.handleTrafficLightInfo(broadcast(values))
            assertThat(sources.snapshot().navigationSignal).isNull()
        }
    }

    @Test
    fun `explicit cruise guide cannot overwrite route location limits or source clocks`() {
        // Given
        handler.handleGuideInfo(broadcast(mapOf("ICON" to 3, "SEG_REMAIN_DIS" to 500,
            "CUR_ROAD_NAME" to "Route road", "LIMITED_SPEED" to 80)))
        val before = fields.value
        val clocks = sources.snapshot()
        // When
        val cruise = broadcast(mapOf("ICON" to 0, "NEW_ICON" to 0,
            "SEG_REMAIN_DIS" to 5, "LIMITED_SPEED" to 20, "CUR_ROAD_NAME" to "Cruise road",
            "endPOIName" to "Wrong destination", "CAR_LATITUDE" to 30.0,
            "CAR_LONGITUDE" to 120.0, "EXTRA_DRIVE_WAY" to "[]"))
        handler.handleGuideInfo(cruise)
        assertThat(handler.hasDriveWayPayload(cruise)).isFalse()
        handler.handleDriveWayInfo(cruise)
        handler.handleDestinationInfo(cruise)
        // Then
        assertThat(fields.value).isSameInstanceAs(before)
        assertThat(sources.snapshot().copy(cruiseRoad = null)).isEqualTo(clocks)
        assertThat(sources.snapshot().cruiseRoad!!.roadName).isEqualTo("Cruise road")
        verify(exactly = 0) { Log.e(any(), any(), any()) }
    }

    @Test
    fun `cruise alone cannot start navigation but missing or unknown icons retain guide behavior`() {
        // Given / When
        handler.handleGuideInfo(broadcast(mapOf("ICON" to 0)))
        handler.handleGuideInfo(broadcast(mapOf("NEW_ICON" to 0)))
        // Then
        assertThat(fields.value.isNavigating).isFalse()
        assertThat(sources.snapshot().updatedAt).isEmpty()
        listOf(emptyMap(), mapOf("ICON" to 999), mapOf("ICON" to "bad"),
            mapOf("ICON" to 0.5), mapOf("ICON" to 0, "NEW_ICON" to "bad")).forEach {
            handler.handleGuideInfo(broadcast(it))
            assertThat(fields.value.isNavigating).isTrue()
        }
    }

    @Test
    fun `NEW_ICON zero falls back to navigation ICON and positive NEW_ICON wins`() {
        // Given / When / Then
        listOf(mapOf("ICON" to 3, "NEW_ICON" to 0), mapOf("ICON" to 0, "NEW_ICON" to 3)).forEach {
            handler.handleGuideInfo(broadcast(it + ("SEG_REMAIN_DIS" to 500)))
            assertThat(sources.snapshot().guideExtras!!.currentIcon).isEqualTo(3)
        }
    }

    @Test
    fun `multi lights update isolated state without touching nav light or route clocks`() {
        // Given
        fields.value = fields.value.copy(trafficLightState = 2, trafficLightCountdown = 8)
        sources.update("traffic_signal", nowMs = 1)
        val before = fields.value
        val clocks = sources.snapshot().updatedAt
        // When
        handler.handleTrafficLightInfo(broadcast(mapOf("lightsData" to lights,
            "trafficLightStatus" to 1, "dir" to 4)))
        // Then
        assertThat(fields.value).isSameInstanceAs(before)
        assertThat(sources.snapshot().updatedAt).isEqualTo(clocks)
        assertThat(sources.snapshot().cruiseLights!!.lamps).containsExactly(
            AmapCruiseLamp(0, 1, 10), AmapCruiseLamp(1, 2, 12),
        ).inOrder()
        assertThat(sources.snapshot().cruiseLights!!.receivedAtMs).isEqualTo(time)
    }

    @Test
    fun `invalid multi lights clear only cruise state and log instead of falling back to nav`() {
        // Given / When / Then
        listOf("bad", "", """[{"status":0,"dir":1}]""").forEach {
            handler.handleTrafficLightInfo(broadcast(mapOf("lightsData" to lights)))
            val before = fields.value
            handler.handleTrafficLightInfo(broadcast(mapOf("lightsData" to it, "trafficLightStatus" to 1)))
            assertThat(sources.snapshot().cruiseLights).isNull()
            assertThat(fields.value).isSameInstanceAs(before)
        }
        verify(exactly = 3) { Log.e(any(), any(), any()) }
    }

    @Test
    fun `inactive source cannot start end or overwrite route and cruise data`() {
        // Given
        handler.handleGuideInfo(broadcast(mapOf("ICON" to 3, "SEG_REMAIN_DIS" to 500)))
        handler.handleTrafficLightInfo(broadcast(mapOf("lightsData" to lights)))
        val before = fields.value
        val snapshot = sources.snapshot()
        selected = false
        assertThat(handler.hasDriveWayPayload(broadcast(mapOf("EXTRA_DRIVE_WAY" to "[]")))).isFalse()
        // When
        handler.handleGuideInfo(broadcast(mapOf("ICON" to 2)))
        handler.handleTrafficLightInfo(broadcast(mapOf("lightsData" to "bad")))
        handler.handleTrafficLightInfo(broadcast(mapOf("trafficLightStatus" to 1)))
        listOf(9, 25, 39).forEach { handler.handleMapState(broadcast(mapOf("EXTRA_STATE" to it))) }
        handler.handleNavigationStatus(broadcast(mapOf("NAVI_STATUS" to 0)))
        handler.handleStartNavigation(broadcast(emptyMap()))
        handler.handleStopNavigation(broadcast(emptyMap()))
        handler.handleDriveWayInfo(broadcast(mapOf("EXTRA_DRIVE_WAY" to "[]")))
        handler.handleRouteInfo(broadcast(mapOf("ROUTE_DISTANCE" to 20000)))
        // Then
        assertThat(fields.value).isSameInstanceAs(before)
        assertThat(sources.snapshot()).isEqualTo(snapshot)
        verify(exactly = 0) { Log.e(any(), any(), any()) }
    }

    @Test
    fun `navigation end withdraws clocks and route data and rejects late guide lights and lanes`() {
        // Given / When / Then
        listOf(9, 39).forEach { state ->
            handler.handleNavigationStatus(broadcast(mapOf("NAVI_STATUS" to 1)))
            handler.handleGuideInfo(broadcast(mapOf("ICON" to 3, "SEG_REMAIN_DIS" to 500,
                "LIMITED_SPEED" to 80)))
            val generation = fields.value.noaRouteGeneration
            handler.handleTrafficLightInfo(broadcast(mapOf("lightsData" to lights)))
            handler.handleMapState(broadcast(mapOf("EXTRA_STATE" to state)))
            assertThat(fields.value.isNavigating).isFalse()
            assertThat(fields.value.noaRouteGeneration).isEqualTo(generation + 1)
            assertThat(fields.value.nRoadLimitSpeed).isEqualTo(0)
            assertThat(sources.snapshot().guideExtras).isNull()
            assertThat(sources.snapshot().cruiseLights).isNull()
            assertThat(sources.snapshot().updatedAt.keys).doesNotContain("guidance_current")
            val stopped = fields.value
            assertThat(handler.hasDriveWayPayload(broadcast(mapOf("EXTRA_DRIVE_WAY" to "[]")))).isFalse()
            handler.handleGuideInfo(broadcast(mapOf("ICON" to 3, "SEG_REMAIN_DIS" to 50)))
            handler.handleTrafficLightInfo(broadcast(mapOf("trafficLightStatus" to 1)))
            handler.handleDriveWayInfo(broadcast(mapOf("EXTRA_DRIVE_WAY" to "[]")))
            handler.handleRouteInfo(broadcast(mapOf("ROUTE_DISTANCE" to 20000)))
            handler.handleSpeedLimit(broadcast(mapOf("LIMITED_SPEED" to 20)))
            assertThat(fields.value).isSameInstanceAs(stopped)
        }
    }

    @Test
    fun `cruise end clears only cruise and late cruise cannot erase active navigation`() {
        // Given
        handler.handleGuideInfo(broadcast(mapOf("ICON" to 3, "SEG_REMAIN_DIS" to 500)))
        handler.handleTrafficLightInfo(broadcast(mapOf("lightsData" to lights)))
        val before = fields.value
        val clocks = sources.snapshot().updatedAt
        // When
        handler.handleMapState(broadcast(mapOf("EXTRA_STATE" to 25)))
        handler.handleTrafficLightInfo(broadcast(mapOf("lightsData" to lights)))
        // Then
        assertThat(sources.snapshot().cruiseLights).isNull()
        assertThat(fields.value).isSameInstanceAs(before)
        assertThat(sources.snapshot().updatedAt).isEqualTo(clocks)
        time += 3000
        handler.handleTrafficLightInfo(broadcast(mapOf("lightsData" to lights)))
        assertThat(sources.snapshot().cruiseLights).isNotNull()
    }

    @Test
    fun `explicit start permits immediate fresh guide and ordinary guide resumes after end holdoff`() {
        // Given
        handler.handleMapState(broadcast(mapOf("EXTRA_STATE" to 9)))
        // When / Then
        time += 2999
        handler.handleGuideInfo(broadcast(mapOf("ICON" to 3)))
        assertThat(fields.value.isNavigating).isFalse()
        time += 1
        handler.handleGuideInfo(broadcast(mapOf("ICON" to 3)))
        assertThat(fields.value.isNavigating).isTrue()
        handler.handleMapState(broadcast(mapOf("EXTRA_STATE" to 9)))
        handler.handleNavigationStatus(broadcast(mapOf("NAVI_STATUS" to 1)))
        handler.handleGuideInfo(broadcast(mapOf("ICON" to 2, "SEG_REMAIN_DIS" to 100)))
        assertThat(sources.snapshot().guideExtras!!.currentIcon).isEqualTo(2)
    }

    @Test
    fun `cruise road replaces missing invalid values and does not send commands or touch navigation`() {
        val before = fields.value
        val network = mockk<NetworkManager>(relaxed = true)
        val cruiseHandler = AmapBroadcastHandlers(fields, network, naviSources = sources,
            isAmapSelected = { selected }, nowMs = { time })
        cruiseHandler.handleGuideInfo(broadcast(mapOf("ICON" to 0, "CUR_ROAD_NAME" to "Road", "CUR_SPEED" to 42.5,
            "CAR_DIRECTION" to 359.5, "CAMERA_TYPE" to 98, "CAMERA_DIST" to 0, "CAMERA_SPEED" to 80)))
        val road = sources.snapshot().cruiseRoad!!
        assertThat(road.speedKph).isEqualTo(42.5)
        assertThat(road.headingDeg).isEqualTo(359.5)
        assertThat(road.cameraType).isEqualTo(98)
        assertThat(road.cameraDistanceM).isEqualTo(0.0)
        assertThat(road.cameraLimitKph).isEqualTo(80)
        time++
        cruiseHandler.handleGuideInfo(broadcast(mapOf("ICON" to 0, "CUR_SPEED" to Double.NaN,
            "CAR_DIRECTION" to 360, "CAMERA_DIST" to -1, "CAMERA_SPEED" to 250)))
        val replacement = sources.snapshot().cruiseRoad!!
        assertThat(replacement.roadName).isNull()
        assertThat(replacement.speedKph).isNull()
        assertThat(replacement.headingDeg).isNull()
        assertThat(replacement.cameraType).isNull()
        assertThat(replacement.cameraDistanceM).isNull()
        assertThat(replacement.cameraLimitKph).isNull()
        assertThat(replacement.receivedAtMs).isEqualTo(time)
        assertThat(fields.value).isSameInstanceAs(before)
        assertThat(sources.snapshot().updatedAt).isEmpty()
        assertThat(sources.snapshot().mapPosition).isNull()
        assertThat(sources.snapshot().guideExtras).isNull()
        verify { network wasNot io.mockk.Called }
    }

    @Test
    fun `cruise road selected source explicit icon stop end holdoff and rollback gates`() {
        handler.handleGuideInfo(broadcast(mapOf("NEW_ICON" to 0)))
        assertThat(sources.snapshot().cruiseRoad).isNull()
        selected = false
        handler.handleGuideInfo(broadcast(mapOf("ICON" to 0)))
        assertThat(sources.snapshot().cruiseRoad).isNull()
        selected = true
        handler.handleGuideInfo(broadcast(mapOf("ICON" to 0)))
        handler.handleMapState(broadcast(mapOf("EXTRA_STATE" to 25)))
        handler.handleGuideInfo(broadcast(mapOf("ICON" to 0)))
        assertThat(sources.snapshot().cruiseRoad).isNull()
        time += 2999
        handler.handleGuideInfo(broadcast(mapOf("ICON" to 0)))
        assertThat(sources.snapshot().cruiseRoad).isNull()
        time++
        handler.handleGuideInfo(broadcast(mapOf("ICON" to 0)))
        assertThat(sources.snapshot().cruiseRoad).isNotNull()
        handler.handleMapState(broadcast(mapOf("EXTRA_STATE" to 9)))
        handler.handleGuideInfo(broadcast(mapOf("ICON" to 0)))
        assertThat(sources.snapshot().cruiseRoad).isNull()
        time -= 5000
        handler.handleGuideInfo(broadcast(mapOf("ICON" to 0)))
        assertThat(sources.snapshot().cruiseRoad).isNull()
    }

    @Test
    fun `section phase uses camera and numeric flags and raw texts never become numbers`() {
        handler.handleGuideInfo(broadcast(mapOf("ICON" to 3, "CAMERA_TYPE" to 8, "CAMERA_SPEED" to 80,
            "START_DISTANCE_TEXT" to "前方 2公里", "END_DISTANCE_TEXT" to "约3公里")))
        val approaching = sources.snapshot().sectionDisplay!!
        assertThat(approaching.phase).isEqualTo(com.jixiexiaoge.drivingassist.navigation.AmapSectionPhase.Approaching)
        assertThat(approaching.startDistanceText).isEqualTo("前方 2公里")
        assertThat(approaching.remainingM).isNull()
        assertThat(approaching.limitKph).isEqualTo(80)
        handler.handleSpeedLimit(broadcast(mapOf("END_DISTANCE" to 1500, "START_DISTANCE" to 500,
            "INTERVAL_DISTANCE" to 2000, "LIMITED_SPEED" to 80,
            "AVERAGE_SPEED" to 65, "END_DISTANCE_TEXT" to "还剩1.5公里")))
        val inside = sources.snapshot().sectionDisplay!!
        assertThat(inside.phase).isEqualTo(com.jixiexiaoge.drivingassist.navigation.AmapSectionPhase.Inside)
        assertThat(inside.remainingM).isEqualTo(1500.0)
        assertThat(inside.startDistanceText).isNull()
        assertThat(inside.averageKph).isEqualTo(65.0)
        assertThat(inside.progress).isEqualTo(0.25)
        handler.handleSpeedLimit(broadcast(mapOf("END_DISTANCE" to 1400)))
        assertThat(sources.snapshot().sectionDisplay!!.progress).isNull()
        assertThat(sources.snapshot().sectionDisplay!!.averageKph).isNull()
        handler.handleGuideInfo(broadcast(mapOf("ICON" to 3, "CAMERA_TYPE" to 9)))
        val ended = sources.snapshot().sectionDisplay!!
        assertThat(ended.phase).isEqualTo(com.jixiexiaoge.drivingassist.navigation.AmapSectionPhase.Ended)
        assertThat(ended.averageKph).isNull()
        assertThat(ended.endDistanceText).isNull()
        handler.handleSpeedLimit(broadcast(mapOf("AVERAGE_SPEED" to 50, "END_DISTANCE_TEXT" to "200米")))
        assertThat(sources.snapshot().sectionDisplay).isNull()
    }

    @Test
    fun `raw section text length is bounded and facilities keep raw and numeric distances independently`() {
        handler.handleGuideInfo(broadcast(mapOf("ICON" to 3, "CAMERA_TYPE" to 8,
            "START_DISTANCE_TEXT" to "x".repeat(81), "END_DISTANCE_TEXT" to "未知",
            "SAPA_NAME" to "Current", "SAPA_DIST" to 0, "SAPA_DIST_AUTO" to "就在前方",
            "NEXT_SAPA_NAME" to "Next", "NEXT_SAPA_DIST" to 2000, "NEXT_SAPA_DIST_AUTO" to "约2公里",
            "SAPA_TYPE" to 998, "NEXT_SAPA_TYPE" to 999)))
        val section = sources.snapshot().sectionDisplay!!
        assertThat(section.startDistanceText).isNull()
        assertThat(section.endDistanceText).isEqualTo("未知")
        val facilities = sources.snapshot().guideExtras!!
        assertThat(facilities.serviceDistanceM).isEqualTo(0)
        assertThat(facilities.serviceDistanceText).isEqualTo("就在前方")
        assertThat(facilities.nextServiceDistanceM).isEqualTo(2000)
        assertThat(facilities.nextServiceDistanceText).isEqualTo("约2公里")
        assertThat(facilities.serviceType).isEqualTo(998)
        assertThat(facilities.nextServiceType).isEqualTo(999)
        handler.handleGuideInfo(broadcast(mapOf("ICON" to 3, "SAPA_DIST" to "2公里",
            "NEXT_SAPA_DIST" to 2.5, "END_DISTANCE_TEXT" to "200米")))
        val replacement = sources.snapshot().guideExtras!!
        assertThat(replacement.serviceDistanceM).isNull()
        assertThat(replacement.serviceDistanceText).isNull()
        assertThat(replacement.nextServiceDistanceM).isNull()
        assertThat(replacement.nextServiceDistanceText).isNull()
        assertThat(replacement.nextServiceType).isNull()
        assertThat(sources.snapshot().sectionDisplay).isNull()
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
}
