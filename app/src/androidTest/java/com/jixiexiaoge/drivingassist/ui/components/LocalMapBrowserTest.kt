package com.jixiexiaoge.drivingassist.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.jixiexiaoge.drivingassist.ui.theme.NavipilotTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * 实时页左格内嵌浏览器（7.9.60）的三态：
 * 没广播 → 请启动高德（点跳投屏页）；探测中；六次都失败 → 投屏功能未开启。
 *
 * 探测成功那一态不在这里断言：真机上要真有 127.0.0.1:8080 的服务，
 * 测试环境没有；WebView 只能在真机上看（见 docs/realtime-page-audit-2026-10-23.html）。
 */
class LocalMapBrowserTest {
    @get:Rule
    val compose = createComposeRule()

    private fun panel(active: Boolean, probe: (Int) -> Boolean = { false }, onClick: () -> Unit = {}) {
        compose.setContent {
            NavipilotTheme {
                Box(Modifier.width(323.dp).height(200.dp)) {
                    LocalMapBrowserPanel(amapActive = active, onOpenProjection = onClick,
                        probe = probe, serverPort = 8087)
                }
            }
        }
    }

    @Test
    fun withoutAmapBroadcastItAsksToStartAmapAndOpensProjection() {
        var taps = 0
        panel(active = false, onClick = { taps++ })

        compose.onNodeWithTag("map-browser-start-amap").assertIsDisplayed()
        compose.onNodeWithText("请启动高德地图车机版").assertExists()
        compose.onNodeWithTag("map-browser-start-amap").performClick()
        assertEquals("点整块提示应跳到投屏页", 1, taps)
        // 没广播时绝不能先去探端口（白等六次）
        compose.onNodeWithTag("map-browser-probing").assertDoesNotExist()
    }

    @Test
    fun threeFailedHealthProbesEndWithProjectionOffNotice() {
        panel(active = true, probe = { false })

        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("map-browser-unavailable").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("map-browser-unavailable").assertIsDisplayed()
        compose.onNodeWithText("当前地图投屏功能未开启，请检查！").assertExists()
    }

    @Test
    fun probesOnlyThePublishedPortEvenBeyond8081() {
        val ports = java.util.Collections.synchronizedList(mutableListOf<Int>())
        panel(active = true, probe = { p ->
            ports += p
            false
        })

        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("map-browser-unavailable").fetchSemanticsNodes().isNotEmpty()
        }
        val order = synchronized(ports) { ports.toList() }
        assertEquals(
            "只探测本进程发布的真实端口，实际 $order",
            listOf(8087, 8087, 8087),
            order,
        )
    }

    @Test
    fun probingStateIsShownWhilePortsAreBeingTried() {
        // 探测故意慢 3 秒（不返回前一直停在 Probing），足够断言提示文案
        panel(active = true, probe = {
            Thread.sleep(3_000)
            true
        })

        compose.onNodeWithTag("map-browser-probing").assertExists()
        compose.onNodeWithText("正在连接本地地图投屏…").assertExists()
    }
}
