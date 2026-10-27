package com.jixiexiaoge.drivingassist.navigation

import com.google.common.truth.Truth.assertThat
import com.jixiexiaoge.drivingassist.CarrotManFields
import com.jixiexiaoge.drivingassist.LaneInfo
import org.junit.Test

class AmapNavigationDisplayTest {
    @Test
    fun `additional route metrics use source receipt not repaint time and expire with source`() {
        // Given
        val sources = tracker().apply {
            updateGuideExtras(extras().copy(currentSegmentSec = 45, nextSegmentSec = 90, totalTimeSec = 1800))
        }
        // When / Then
        val display = buildAmapNavigationDisplay(fields(), sources.snapshot(), "AMAP", 1200)
        assertThat(display.totalDistanceM).isEqualTo(20000)
        assertThat(display.traveledDistanceM).isEqualTo(8000)
        assertThat(display.arrivalAtMs).isEqualTo(sources.snapshot().guideExtras!!.receivedAtMs + 900000)
        assertThat(display.totalTimeSec).isEqualTo(1800)
        assertThat(display.current!!.remainingSec).isEqualTo(45)
        assertThat(display.next!!.remainingSec).isEqualTo(90)
        assertThat(buildAmapNavigationDisplay(fields(), sources.snapshot(), "AMAP", 2200).arrivalAtMs)
            .isEqualTo(display.arrivalAtMs)
        listOf(
            buildAmapNavigationDisplay(fields(), sources.snapshot(), "AMAP",
                sources.snapshot().guideExtras!!.receivedAtMs + NaviV2Constants.STALE_TIMEOUT_MS + 1),
            buildAmapNavigationDisplay(fields(), sources.snapshot(), "OTHER", 1200),
            buildAmapNavigationDisplay(fields().copy(noaRouteGeneration = 2), sources.snapshot(), "AMAP", 1200),
        ).forEach {
            assertThat(it.totalDistanceM).isNull()
            assertThat(it.traveledDistanceM).isNull()
            assertThat(it.arrivalAtMs).isNull()
            assertThat(it.totalTimeSec).isNull()
        }
        sources.updateRouteTotal(10000)
        assertThat(buildAmapNavigationDisplay(fields(), sources.snapshot(), "AMAP", 1200).traveledDistanceM).isNull()
    }

    @Test
    fun `action time accepts reported zero but does not survive a route replacement`() {
        // Given
        val sources = tracker().apply {
            updateGuideExtras(extras().copy(currentSegmentSec = 0, totalTimeSec = 100))
        }
        // When / Then
        val display = buildAmapNavigationDisplay(fields(), sources.snapshot(), "AMAP", 1200)
        assertThat(display.current!!.remainingSec).isEqualTo(0)
        assertThat(display.totalTimeSec).isNull()
        sources.resetGuideDisplay(1300)
        assertThat(buildAmapNavigationDisplay(fields(), sources.snapshot(), "AMAP", 1400).arrivalAtMs).isNull()
        assertThat(buildAmapNavigationDisplay(fields(), sources.snapshot(), "AMAP", 1400).current).isNull()
    }

    @Test
    fun `original road limit never falls back to corrected output or vehicle telemetry`() {
        // Given
        val sources = tracker().apply { updateRoadSpeedLimit(40, 1, 1000) }
        val state = fields().copy(nRoadLimitSpeed = 60)
        // When / Then
        assertThat(buildAmapNavigationDisplay(state, sources.snapshot(), "AMAP", 1200).roadLimitKph).isEqualTo(40)
        sources.updateRoadSpeedLimit(0, 1, 1300)
        sources.update("road_limit", "vehicle", nowMs = 1400)
        assertThat(buildAmapNavigationDisplay(state, sources.snapshot(), "AMAP", 1500).roadLimitKph).isNull()
        listOf(-1, 250, 999).forEach {
            sources.updateRoadSpeedLimit(it, 1, 1500)
            assertThat(buildAmapNavigationDisplay(state, sources.snapshot(), "AMAP", 1600).roadLimitKph).isNull()
        }
    }

    @Test
    fun `original road limit obeys source generation route boundary and exact TTL`() {
        // Given
        val sources = NaviSourceTracker().apply { updateRoadSpeedLimit(80, 1, 1000) }
        // When / Then
        val boundary = buildAmapNavigationDisplay(fields(), sources.snapshot(), "AMAP", 11000)
        assertThat(boundary.roadLimitKph).isEqualTo(80)
        assertThat(boundary.state).isEqualTo(AmapDisplayState.Fresh)
        val expired = buildAmapNavigationDisplay(fields(), sources.snapshot(), "AMAP", 11001)
        assertThat(expired.state).isEqualTo(AmapDisplayState.Expired)
        listOf(
            expired,
            buildAmapNavigationDisplay(fields(), sources.snapshot(), "OTHER", 1200),
            buildAmapNavigationDisplay(fields().copy(isNavigating = false), sources.snapshot(), "AMAP", 1200),
            buildAmapNavigationDisplay(fields().copy(noaRouteGeneration = 2), sources.snapshot(), "AMAP", 1200),
            buildAmapNavigationDisplay(fields(), sources.snapshot(), "AMAP", 999),
        ).forEach { assertThat(it.roadLimitKph).isNull() }
        sources.update("ui_route_changed", nowMs = 1100)
        assertThat(buildAmapNavigationDisplay(fields(), sources.snapshot(), "AMAP", 1200).roadLimitKph).isNull()
        sources.updateRoadSpeedLimit(60, 1, 1100)
        assertThat(buildAmapNavigationDisplay(fields(), sources.snapshot(), "AMAP", 1200).roadLimitKph).isEqualTo(60)
    }

    @Test
    fun `upcoming limit uses nearest fresh valid speed event with its own distance`() {
        // Given
        val sources = tracker().apply { update("sdi", "sdi_secondary", nowMs = 1000) }
        val state = fields().copy(nSdiType = 1, nSdiDist = 500, nSdiSpeedLimit = 80,
            nSdiPlusType = 2, nSdiPlusDist = 200, nSdiPlusSpeedLimit = 60)
        // When / Then
        val display = buildAmapNavigationDisplay(state, sources.snapshot(), "AMAP", 1200)
        assertThat(display.upcomingLimit!!.limitKph).isEqualTo(60)
        assertThat(display.upcomingLimit!!.distanceM).isEqualTo(200)
        assertThat(display.upcomingLimit!!.type).isEqualTo(2)
        sources.update("sdi", nowMs = 11001)
        assertThat(buildAmapNavigationDisplay(state, sources.snapshot(), "AMAP", 11001).upcomingLimit!!.limitKph)
            .isEqualTo(80)
        assertThat(buildAmapNavigationDisplay(state, sources.snapshot(), "OTHER", 1200).upcomingLimit).isNull()
        assertThat(buildAmapNavigationDisplay(state, sources.snapshot(), "AMAP", 22000).upcomingLimit).isNull()
    }

    @Test
    fun `unknown cameras section end ongoing sections and invalid limits are not upcoming road restrictions`() {
        // Given
        val sources = tracker().apply { update("sdi", nowMs = 1000) }
        // When / Then
        listOf(3, 4, 6, 20, 101, 999).forEach { type ->
            val state = fields().copy(nSdiType = type, nSdiDist = 100, nSdiSpeedLimit = 60)
            assertThat(buildAmapNavigationDisplay(state, sources.snapshot(), "AMAP", 1200).upcomingLimit).isNull()
        }
        listOf(0, 250, -1).forEach { limit ->
            val state = fields().copy(nSdiType = 1, nSdiDist = 100, nSdiSpeedLimit = limit)
            assertThat(buildAmapNavigationDisplay(state, sources.snapshot(), "AMAP", 1200).upcomingLimit).isNull()
        }
        val arrived = fields().copy(nSdiType = 1, nSdiDist = 0, nSdiSpeedLimit = 60)
        assertThat(buildAmapNavigationDisplay(arrived, sources.snapshot(), "AMAP", 1200).upcomingLimit).isNull()
    }

    @Test
    fun `route changes and stopping navigation clear the original road limit snapshot`() {
        // Given / When / Then
        repeat(2) { scenario ->
            val sources = tracker().apply { updateRoadSpeedLimit(80, 1, 1000) }
            if (scenario == 0) sources.resetGuideDisplay(1300) else sources.stopNavigation()
            assertThat(sources.snapshot().roadSpeedLimit).isNull()
            assertThat(buildAmapNavigationDisplay(fields(), sources.snapshot(), "AMAP", 1400).roadLimitKph).isNull()
        }
    }

    @Test
    fun `fresh active broadcasts expose route maneuvers lanes exit and service facility`() {
        // Given / When
        val display = buildAmapNavigationDisplay(fields(), tracker().snapshot(), "AMAP", 1200)
        // Then
        assertThat(display.state).isEqualTo(AmapDisplayState.Fresh)
        assertThat(display.destination).isEqualTo("Test destination")
        assertThat(display.remainingM).isEqualTo(12000)
        assertThat(display.remainingSec).isEqualTo(900)
        assertThat(display.progress).isWithin(0.0001f).of(0.4f)
        assertThat(display.current).isEqualTo(AmapDisplayAction("右转", "Road B", 500, maneuverIcon = 3))
        assertThat(display.next).isEqualTo(AmapDisplayAction("左转", "Road C", 200, maneuverIcon = 2))
        assertThat(display.lanes!!.recommendedIndices).containsExactly(1, 2)
        assertThat(display.exit).isEqualTo("Exit A · East")
        assertThat(display.service).isEqualTo(AmapServiceStop("Test service area", 3000))
        assertThat(display.assistantAction).isEqualTo("靠右行驶")
    }

    @Test
    fun `inactive source and stopped navigation suppress all map hints`() {
        // Given
        val source = tracker().snapshot()
        // When / Then
        val inactive = buildAmapNavigationDisplay(fields(), source, "OTHER", 1200)
        assertThat(inactive.state).isEqualTo(AmapDisplayState.Inactive)
        val idle = buildAmapNavigationDisplay(fields().copy(isNavigating = false), source, "AMAP", 1200)
        assertThat(idle.state).isEqualTo(AmapDisplayState.Idle)
        listOf(inactive, idle).forEach {
            assertThat(it.current).isNull()
            assertThat(it.lanes).isNull()
            assertThat(it.events).isEmpty()
            assertThat(it.service).isNull()
            assertThat(it.remainingM).isNull()
        }
    }

    @Test
    fun `GPS and telemetry freshness never revives old AMap route or lanes`() {
        // Given
        val sources = tracker()
        sources.update("vehicle", "carState", nowMs = 12000)
        // When
        val display = buildAmapNavigationDisplay(fields().copy(lastUpdateTime = 12000), sources.snapshot(), "AMAP", 12000)
        // Then
        assertThat(display.state).isEqualTo(AmapDisplayState.Expired)
        assertThat(display.current).isNull()
        assertThat(display.next).isNull()
        assertThat(display.progress).isNull()
        assertThat(display.lanes).isNull()
        assertThat(display.exit).isNull()
        assertThat(display.service).isNull()
    }

    @Test
    fun `cached or inconsistent lanes cannot become fresh recommendation`() {
        // Given
        val sources = tracker().snapshot()
        // When / Then
        assertThat(buildAmapNavigationDisplay(fields().copy(laneInfoFromCache = true), sources, "AMAP", 1200).lanes).isNull()
        assertThat(buildAmapNavigationDisplay(fields().copy(nLaneCount = 4), sources, "AMAP", 1200).lanes).isNull()
    }

    @Test
    fun `partial guide replacement does not reuse old destination service or route metrics`() {
        // Given
        val sources = tracker()
        sources.updateGuideExtras(extras().copy(receivedAtMs = 1300, destinationName = null,
            roadName = null, remainingDistanceM = null, remainingTimeSec = null,
            exitName = null, exitDirection = null, serviceName = null, serviceDistanceM = null))
        // When
        val display = buildAmapNavigationDisplay(fields(), sources.snapshot(), "AMAP", 1400)
        // Then
        assertThat(display.destination).isNull()
        assertThat(display.roadName).isNull()
        assertThat(display.remainingM).isNull()
        assertThat(display.remainingSec).isNull()
        assertThat(display.progress).isNull()
        assertThat(display.service).isNull()
        assertThat(display.exit).isNull()
    }

    @Test
    fun `guide without maneuver distance cannot turn a previous fresh action into an immediate action`() {
        // Given
        val sources = tracker()
        sources.updateGuideExtras(extras().copy(receivedAtMs = 1300, currentIcon = null))
        // When
        val display = buildAmapNavigationDisplay(fields().copy(nTBTDist = 0), sources.snapshot(), "AMAP", 1400)
        // Then
        assertThat(display.current).isNull()
        assertThat(display.next).isNotNull()
    }

    @Test
    fun `route change suppresses older action lane and supplemental snapshots`() {
        // Given
        val sources = tracker()
        sources.resetGuideDisplay(nowMs = 1250)
        // When / Then
        val changed = fields().copy(noaRouteGeneration = 2)
        val stale = buildAmapNavigationDisplay(changed, sources.snapshot(), "AMAP", 1300)
        assertThat(stale.current).isNull()
        assertThat(stale.lanes).isNull()
        assertThat(stale.remainingM).isNull()
        sources.update("guidance_current", nowMs = 1400)
        sources.updateGuideExtras(extras().copy(receivedAtMs = 1400, routeGeneration = 2))
        val fresh = buildAmapNavigationDisplay(changed, sources.snapshot(), "AMAP", 1500)
        assertThat(fresh.current).isNotNull()
        assertThat(fresh.lanes).isNull()
        assertThat(fresh.remainingM).isEqualTo(12000)
        sources.update("lane_current", nowMs = 1250)
        assertThat(buildAmapNavigationDisplay(changed, sources.snapshot(), "AMAP", 1500).lanes).isNull()
        sources.update("lane_current", nowMs = 1401)
        assertThat(buildAmapNavigationDisplay(changed, sources.snapshot(), "AMAP", 1500).lanes).isNotNull()
    }

    @Test
    fun `stop clears supplemental data even before shared navigation flag updates`() {
        // Given
        val sources = tracker()
        sources.stopNavigation()
        // When
        val display = buildAmapNavigationDisplay(fields(), sources.snapshot(), "AMAP", 1200)
        // Then
        assertThat(sources.snapshot().guideExtras).isNull()
        assertThat(display.current).isNull()
        assertThat(display.lanes).isNull()
        assertThat(display.destination).isNull()
        assertThat(display.progress).isNull()
    }

    @Test
    fun `unknown icon is explicit and newer turn broadcast uses its own instruction`() {
        // Given
        val sources = tracker()
        sources.updateGuideExtras(extras().copy(currentIcon = 999))
        // When / Then
        assertThat(buildAmapNavigationDisplay(fields(), sources.snapshot(), "AMAP", 1200).current!!.label)
            .isEqualTo("未识别的高德动作（ICON 999）")
        sources.update("guidance_current", "guidance_next", nowMs = 1500)
        val display = buildAmapNavigationDisplay(fields().copy(szTBTMainText = "Updated instruction"),
            sources.snapshot(), "AMAP", 1600)
        assertThat(display.current!!.label).isEqualTo("Updated instruction")
        assertThat(display.next!!.label).isEqualTo("未识别的导航动作")
        assertThat(display.next!!.roadName).isNull()
    }

    @Test
    fun `fresh speed events and section values are source gated and retain known zero averages`() {
        // Given
        val sources = tracker()
        sources.updateSection(1000.0, 0.0, 0.5, nowMs = 1000, routeGeneration = 1)
        sources.update("sdi", "section", nowMs = 1000)
        val state = fields().copy(nSdiType = 1, nSdiDist = 300, nSdiSpeedLimit = 60,
            nSdiBlockType = 2, nSdiBlockSpeed = 80)
        // When / Then
        val display = buildAmapNavigationDisplay(state, sources.snapshot(), "AMAP", 1200)
        assertThat(display.events.single().distanceM).isEqualTo(300)
        assertThat(display.section!!.averageKph).isEqualTo(0f)
        assertThat(display.section!!.remainingM).isEqualTo(1000f)
        val expired = buildAmapNavigationDisplay(state, sources.snapshot(), "AMAP", 12000)
        assertThat(expired.events).isEmpty()
        assertThat(expired.section).isNull()
    }

    private fun fields() = CarrotManFields(
        isNavigating = true, noaRouteGeneration = 1, nTBTDist = 500, nTBTTurnType = 13,
        nTBTDistNext = 200, nTBTTurnTypeNext = 12, szNearDirName = "Road B", szFarDirName = "Road C",
        szTBTMainText = "Old instruction", nLaneCount = 3, segAssistantAction = 4,
        laneInfoList = listOf(LaneInfo("1", false), LaneInfo("2", true), LaneInfo("3", true)),
    )

    @Test
    fun `AMap signal countdown uses only its selected fresh source and known lamp`() {
        // Given
        val sources = tracker()
        sources.updateNavigationSignal(1, 4, 12, 1, 1000)
        val red = fields().copy(trafficLightState = 1, trafficLightCountdown = 12, amap_traffic_light_dir = 4)
        // When / Then
        assertThat(buildAmapNavigationDisplay(red, sources.snapshot(), "AMAP", 3000).trafficSignal)
            .isEqualTo(AmapDisplaySignal(1, 4, 10))
        val green = red.copy(trafficLightState = 2, amap_green_light_last_second = 9)
        sources.updateNavigationSignal(2, 4, 9, 1, 1100)
        assertThat(sources.snapshot().navigationSignal).isNull()
        assertThat(buildAmapNavigationDisplay(green, sources.snapshot(), "AMAP", 3100).trafficSignal)
            .isEqualTo(AmapDisplaySignal(2, 4, 7))
        assertThat(buildAmapNavigationDisplay(red, sources.snapshot(), "OTHER", 3000).trafficSignal).isNull()
        assertThat(buildAmapNavigationDisplay(red, sources.snapshot(), "AMAP", 12000).trafficSignal).isNull()
        assertThat(buildAmapNavigationDisplay(red.copy(trafficLightState = 0), sources.snapshot(), "AMAP", 3000)
            .trafficSignal).isEqualTo(AmapDisplaySignal(2, 4, 8))
        assertThat(buildAmapNavigationDisplay(red.copy(isNavigating = false), sources.snapshot(), "AMAP", 3000)
            .trafficSignal).isEqualTo(AmapDisplaySignal(2, 4, 8))
        assertThat(buildAmapNavigationDisplay(red.copy(noaRouteGeneration = 2), sources.snapshot(), "AMAP", 3000)
            .trafficSignal).isNull()
        sources.stopNavigation()
        assertThat(buildAmapNavigationDisplay(red, sources.snapshot(), "AMAP", 3000).trafficSignal).isNull()
    }

    @Test
    fun `signal phase end invalid replacement and source reset withdraw stale shared lamps`() {
        val sources = tracker()
        val stale = fields().copy(trafficLightState = 2, amap_green_light_last_second = 99)
        fun display(now: Long) = buildAmapNavigationDisplay(stale, sources.snapshot(), "AMAP", now).trafficSignal
        sources.updateNavigationSignal(1, 4, 2, 1, 1000)
        assertThat(display(999)).isNull()
        assertThat(display(2999)).isEqualTo(AmapDisplaySignal(1, 4, 1))
        assertThat(display(3000)).isNull()
        sources.updateNavigationSignal(1, 0, 20, 1, 4000)
        assertThat(display(4100)).isEqualTo(AmapDisplaySignal(1, 0, 20))
        sources.updateNavigationSignal(2, 4, 99, 1, 3999)
        assertThat(display(4100)).isEqualTo(AmapDisplaySignal(1, 0, 20))
        sources.updateNavigationSignal(3, 4, 20, 1, 5000)
        assertThat(display(5100)).isNull()
        sources.updateNavigationSignal(2, 1, 20, 1, 6000)
        assertThat(display(6100)).isNotNull()
        sources.updateNavigationSignal(null, null, null, 1, 6200)
        assertThat(display(6300)).isNull()
        sources.updateNavigationSignal(2, 1, 20, 1, 7000)
        sources.invalidate("traffic_signal")
        assertThat(display(7100)).isNull()
        sources.updateNavigationSignal(2, 1, 20, 1, 8000)
        sources.sampleReadOnly(8100, true)
        sources.sampleReadOnly(7900, true)
        assertThat(display(8200)).isNull()
        sources.updateNavigationSignal(2, 1, 20, 1, 9000)
        sources.sampleReadOnly(9100, false)
        assertThat(display(9200)).isNull()
    }

    @Test
    fun `raw road classification is displayed only with fresh selected source evidence`() {
        // Given
        val sources = tracker()
        sources.update("road_category", nowMs = 1000)
        sources.updateGuideExtras(extras().copy(reportedRoadType = 6))
        val state = fields().copy(roadType = 6)
        // When / Then
        assertThat(buildAmapNavigationDisplay(state, sources.snapshot(), "AMAP", 1200).roadScope)
            .isEqualTo("快速路（高架属性未确认）")
        assertThat(buildAmapNavigationDisplay(state, sources.snapshot(), "OTHER", 1200).roadScope).isNull()
        assertThat(buildAmapNavigationDisplay(state, sources.snapshot(), "AMAP", 12000).roadScope).isNull()
    }

    @Test
    fun `complete lane numbers sort directions together with recommendations`() {
        // Given
        val state = fields().copy(laneInfoList = listOf(
            LaneInfo("3", true, driveWayNumber = 2), LaneInfo("0", false, driveWayNumber = 0),
            LaneInfo("2", true, driveWayNumber = 1),
        ))
        // When
        val display = buildAmapNavigationDisplay(state, tracker().snapshot(), "AMAP", 1200)
        // Then
        assertThat(display.junction!!.leftToRightKnown).isTrue()
        assertThat(display.junction!!.lanes.map { it.direction!!.label }).containsExactly("直行", "直行/左转", "右转").inOrder()
        assertThat(display.lanes!!.recommendedIndices).containsExactly(1, 2).inOrder()
        assertThat(display.junction!!.actionDistanceM).isEqualTo(500)
    }

    @Test
    fun `incomplete duplicate and out of range numbers never assert a left to right order`() {
        // Given / When / Then
        listOf(listOf(-1, -1, -1), listOf(0, 0, 2), listOf(1, 2, 3)).forEach { numbers ->
            val state = fields().copy(laneInfoList = numbers.mapIndexed { index, number ->
                LaneInfo("$index", index == 0, driveWayNumber = number)
            })
            val junction = buildAmapNavigationDisplay(state, tracker().snapshot(), "AMAP", 1200).junction!!
            assertThat(junction.leftToRightKnown).isFalse()
            assertThat(junction.lanes.map { it.direction!!.label }).containsExactly("直行", "左转", "直行/左转").inOrder()
            assertThat(junction.lanes.first().recommended).isTrue()
        }
    }

    @Test
    fun `unknown lane direction is retained and a stale action cannot supply junction distance`() {
        // Given
        val sources = tracker().apply { update("lane_current", nowMs = 11500) }
        val state = fields().copy(nLaneCount = 1, laneInfoList = listOf(LaneInfo("", true)))
        // When
        val junction = buildAmapNavigationDisplay(state, sources.snapshot(), "AMAP", 12000).junction!!
        // Then
        assertThat(junction.lanes.single().direction).isNull()
        assertThat(junction.lanes.single().recommended).isTrue()
        assertThat(junction.actionDistanceM).isNull()
    }

    @Test
    fun `road descriptions replace per kind clear on blank and never invent a distance`() {
        // Given
        val sources = tracker().apply {
            updateRoadNotice(AmapRoadNoticeKind.Traffic, "Congestion", null, 1, 1000)
            updateRoadNotice(AmapRoadNoticeKind.Situation, "Merge", -1, 1, 1000)
            updateRoadNotice(AmapRoadNoticeKind.Traffic, "  Slow traffic  ", null, 1, 1100)
        }
        // When / Then
        var notices = buildAmapNavigationDisplay(fields(), sources.snapshot(), "AMAP", 1200).roadNotices
        assertThat(notices.map { it.text }).containsExactly("Slow traffic", "Merge")
        assertThat(notices.mapNotNull { it.distanceM }).isEmpty()
        sources.updateRoadNotice(AmapRoadNoticeKind.Traffic, " ", 100, 1, 1200)
        notices = buildAmapNavigationDisplay(fields(), sources.snapshot(), "AMAP", 1300).roadNotices
        assertThat(notices.single().text).isEqualTo("Merge")
    }

    @Test
    fun `road notices and TMC obey exact TTL selected source navigation and generation gates`() {
        // Given
        val sources = NaviSourceTracker().apply {
            updateRoadNotice(AmapRoadNoticeKind.Situation, "Road works", 500, 1, 1000)
            updateTrafficSegments(AmapTrafficSegments(1000, 0, listOf(AmapTrafficSegment(1, 1000)), 1, 1000))
        }
        // When / Then
        val boundary = buildAmapNavigationDisplay(fields(), sources.snapshot(), "AMAP", 11000)
        assertThat(boundary.roadNotices.single().distanceM).isEqualTo(500)
        assertThat(boundary.trafficSegments).isNotNull()
        assertThat(boundary.state).isEqualTo(AmapDisplayState.Fresh)
        val expired = buildAmapNavigationDisplay(fields(), sources.snapshot(), "AMAP", 11001)
        assertThat(expired.state).isEqualTo(AmapDisplayState.Expired)
        listOf(expired,
            buildAmapNavigationDisplay(fields(), sources.snapshot(), "OTHER", 1200),
            buildAmapNavigationDisplay(fields().copy(isNavigating = false), sources.snapshot(), "AMAP", 1200),
            buildAmapNavigationDisplay(fields().copy(noaRouteGeneration = 2), sources.snapshot(), "AMAP", 1200),
            buildAmapNavigationDisplay(fields(), sources.snapshot(), "AMAP", 999),
        ).forEach {
            assertThat(it.roadNotices).isEmpty()
            assertThat(it.trafficSegments).isNull()
        }
    }

    @Test
    fun `route changes and stops clear all supplemental road information`() {
        // Given / When / Then
        repeat(2) { scenario ->
            val sources = tracker().apply {
                updateRoadNotice(AmapRoadNoticeKind.Traffic, "Slow", null, 1, 1000)
                updateTrafficSegments(AmapTrafficSegments(1000, 0, listOf(AmapTrafficSegment(1, 1000)), 1, 1000))
            }
            if (scenario == 0) sources.resetGuideDisplay(1300) else sources.stopNavigation()
            assertThat(sources.snapshot().roadNotices).isEmpty()
            assertThat(sources.snapshot().trafficSegments).isNull()
        }
    }

    @Test
    fun `empty event broadcasts remain distinguishable from missing expired and inactive sources`() {
        // Given
        val sources = tracker().apply { update("sdi", "section", nowMs = 1000) }
        val state = fields().copy(nSdiType = -1, nSdiDist = 0, nSdiBlockType = -1)
        // When / Then
        val fresh = buildAmapNavigationDisplay(state, sources.snapshot(), "AMAP", 1200)
        assertThat(fresh.events).isEmpty()
        assertThat(fresh.section).isNull()
        assertThat(fresh.speedEventsFresh).isTrue()
        assertThat(fresh.sectionFresh).isTrue()
        listOf(
            buildAmapNavigationDisplay(state, sources.snapshot(), "AMAP", 11001),
            buildAmapNavigationDisplay(state, sources.snapshot(), "OTHER", 1200),
            buildAmapNavigationDisplay(state.copy(isNavigating = false), sources.snapshot(), "AMAP", 1200),
            buildAmapNavigationDisplay(state, NaviSourceTracker().snapshot(), "AMAP", 1200),
        ).forEach {
            assertThat(it.speedEventsFresh).isFalse()
            assertThat(it.sectionFresh).isFalse()
        }
    }

    @Test
    fun `a fresh standalone road or event broadcast updates the overview without a guide packet`() {
        // Given / When / Then
        listOf("guidance_next", "road_category", "sdi", "sdi_secondary", "section").forEach { name ->
            val sources = NaviSourceTracker().apply { update(name, nowMs = 1000) }
            assertThat(buildAmapNavigationDisplay(fields(), sources.snapshot(), "AMAP", 1200).state)
                .isEqualTo(AmapDisplayState.Fresh)
            assertThat(buildAmapNavigationDisplay(fields(), sources.snapshot(), "AMAP", 11001).state)
                .isEqualTo(AmapDisplayState.Expired)
        }
    }

    private fun tracker() = NaviSourceTracker().apply {
        update("guidance_current", "guidance_next", "route", "lane_current", nowMs = 1000)
        updateRouteTotal(20000)
        updateGuideExtras(extras())
    }

    private fun extras() = AmapGuideExtras(
        1100, 1, "Road A", "Test destination", 12000, 900, 3, 2,
        "Exit A", "East", "Test service area", 3000,
    )
}
