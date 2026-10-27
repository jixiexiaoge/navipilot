package com.jixiexiaoge.drivingassist.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.jixiexiaoge.drivingassist.ui.theme.NavipilotTheme
import org.junit.Rule
import org.junit.Test

/**
 * 实时页「地图格」的出现条件（8.0.26 回归测试）。
 *
 * 修复的 bug：投屏模式是**独立于高德导航**的链路。原来地图格只在有高德显示模型时才出现，
 * 而且内嵌面板还要求「高德广播新鲜」，于是「只开投屏、没开高德导航」的用户
 * 看到的是「请启动高德地图车机版」，永远不会去连已经起好的投屏服务。
 */
class RealTimePageMapPaneTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun projectionRunningWithoutNavigationStillShowsMapPane() {
        compose.setContent {
            NavipilotTheme {
                RealTimePage(
                    host = null,
                    snapshot = null,
                    amap = null,
                    // 模拟「投屏服务已在跑」：端口非空
                    projection = MapProjectionViewport(port = 8080, aspect = 4f / 3f),
                )
            }
        }

        // 地图行出现（右侧画面格是它的兄弟格）
        compose.onNodeWithTag("navi-map-row").assertIsDisplayed()
        compose.onNodeWithTag("navi-video-pane").assertIsDisplayed()
        compose.onNodeWithTag("local-map-browser").assertIsDisplayed()
        // 不应该再提示用户去启动高德——投屏已经在跑了
        compose.onNodeWithText("请启动高德地图车机版", substring = true).assertDoesNotExist()
    }

    @Test
    fun noNavigationAndNoProjectionKeepsCameraOnlyLayout() {
        compose.setContent {
            NavipilotTheme {
                RealTimePage(
                    host = null,
                    snapshot = null,
                    amap = null,
                    projection = MapProjectionViewport(),
                )
            }
        }

        // 既没导航也没投屏：不占两格，画面独占（此处 host 为空，只验证地图行不存在）
        compose.onNodeWithTag("navi-map-row").assertDoesNotExist()
        compose.onNodeWithTag("local-map-browser").assertDoesNotExist()
    }
}
