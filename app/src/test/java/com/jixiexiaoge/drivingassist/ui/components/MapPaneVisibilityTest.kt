package com.jixiexiaoge.drivingassist.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 地图格的可见性（8.0.26）。
 *
 * 这里守的是一个曾经的真实 bug：投屏模式是**独立于高德导航**的链路，
 * 判断「要不要显示地图」时不能只看高德广播，否则用户开了投屏却看不到地图。
 */
class MapPaneVisibilityTest {

    @Test
    fun projectionRunningAloneIsEnough() {
        // 只开了投屏、高德没在导航：必须显示（修复前这里是被判成 false 的）
        assertTrue(mapPaneVisible(hasAmapDisplay = false, projectionPort = 8080))
        assertTrue(mapPaneVisible(hasAmapDisplay = false, projectionPort = 8081))
    }

    @Test
    fun amapDisplayAloneIsEnough() {
        // 有导航数据但投屏没开：仍然显示地图格（内部会提示「投屏未开启」）
        assertTrue(mapPaneVisible(hasAmapDisplay = true, projectionPort = null))
    }

    @Test
    fun neitherMeansNoMapPane() {
        // 既没导航、投屏也没开：不占位置，画面独占一行
        assertFalse(mapPaneVisible(hasAmapDisplay = false, projectionPort = null))
    }

    @Test
    fun bothPresentStaysVisible() {
        assertTrue(mapPaneVisible(hasAmapDisplay = true, projectionPort = 8080))
    }
}
