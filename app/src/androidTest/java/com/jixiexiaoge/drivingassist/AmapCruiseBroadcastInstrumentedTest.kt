package com.jixiexiaoge.drivingassist

import android.content.Intent
import androidx.compose.runtime.mutableStateOf
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertSame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import com.jixiexiaoge.drivingassist.navigation.AmapCruiseLamp
import com.jixiexiaoge.drivingassist.navigation.NaviSourceTracker
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AmapCruiseBroadcastInstrumentedTest {
    @Test
    fun guideReceiptsAndIndependentSegmentTimesUseInjectedIngressClock() {
        val fields = mutableStateOf(CarrotManFields())
        val sources = NaviSourceTracker()
        val handler = AmapBroadcastHandlers(fields, naviSources = sources, nowMs = { 1000L })
        handler.handleGuideInfo(Intent().apply {
            putExtra("ICON", 3)
            putExtra("SEG_REMAIN_DIS", 500)
            putExtra("SEG_REMAIN_TIME", 80)
            putExtra("NEXT_NEXT_TURN_ICON", 2)
            putExtra("NEXT_SEG_REMAIN_DIS", 200)
            putExtra("NEXT_SEG_REMAIN_TIME", 15)
            putExtra("LIMITED_SPEED", 100)
        })
        val snapshot = sources.snapshot()
        assertEquals(1000L, snapshot.updatedAt["guidance_current"])
        assertEquals(1000L, snapshot.updatedAt["guidance_next"])
        assertEquals(1000L, snapshot.guideExtras!!.receivedAtMs)
        assertEquals(80, snapshot.guideExtras!!.currentSegmentSec)
        assertEquals(15, snapshot.guideExtras!!.nextSegmentSec)
        assertEquals(100, snapshot.roadSpeedLimit!!.limitKph)
        assertEquals(1000L, snapshot.roadSpeedLimit!!.receivedAtMs)
        handler.handleGuideInfo(Intent().apply {
            putExtra("ICON", 3)
            putExtra("SEG_REMAIN_DIS", 400)
        })
        assertNull(sources.snapshot().guideExtras!!.currentSegmentSec)
        assertNull(sources.snapshot().guideExtras!!.nextSegmentSec)
        assertNull(sources.snapshot().roadSpeedLimit)
    }
    @Test
    fun cruiseGuideAndMultiLightsDoNotOverwriteRealNavigationIntent() {
        val fields = mutableStateOf(CarrotManFields())
        val sources = NaviSourceTracker()
        val handler = AmapBroadcastHandlers(fields, naviSources = sources)
        handler.handleGuideInfo(Intent().apply {
            putExtra("ICON", 3)
            putExtra("SEG_REMAIN_DIS", 500)
            putExtra("CUR_ROAD_NAME", "Navigation road")
            putExtra("LIMITED_SPEED", 80)
        })
        val before = fields.value
        val clocks = sources.snapshot().updatedAt
        handler.handleGuideInfo(Intent().apply {
            putExtra("ICON", 0)
            putExtra("CUR_ROAD_NAME", "Cruise road")
            putExtra("LIMITED_SPEED", 20)
        })
        handler.handleTrafficLightInfo(Intent().putExtra(
            "lightsData",
            """[{"status":0,"dir":1,"countdown":9},{"status":1,"dir":2,"countdown":7}]"""
        ))
        assertSame(before, fields.value)
        assertEquals(clocks, sources.snapshot().updatedAt)
        assertEquals(listOf(AmapCruiseLamp(0, 1, 9), AmapCruiseLamp(1, 2, 7)),
            sources.snapshot().cruiseLights!!.lamps)
        handler.handleMapState(Intent().putExtra("EXTRA_STATE", 9))
        handler.handleGuideInfo(Intent().putExtra("ICON", 3))
        assertFalse(fields.value.isNavigating)
        assertNull(sources.snapshot().guideExtras)
    }

    @Test
    fun fractionalZeroIconIsNotCruiseEvidenceAndWrongLightPayloadTypeClearsCruise() {
        val fields = mutableStateOf(CarrotManFields())
        val sources = NaviSourceTracker()
        val handler = AmapBroadcastHandlers(fields, naviSources = sources)
        handler.handleGuideInfo(Intent().putExtra("ICON", 0.5))
        assertTrue(fields.value.isNavigating)
        handler.handleTrafficLightInfo(Intent().putExtra(
            "lightsData", """[{"status":0,"dir":1,"countdown":9}]"""
        ))
        handler.handleTrafficLightInfo(Intent().putExtra("lightsData", 7))
        assertNull(sources.snapshot().cruiseLights)
    }
}
