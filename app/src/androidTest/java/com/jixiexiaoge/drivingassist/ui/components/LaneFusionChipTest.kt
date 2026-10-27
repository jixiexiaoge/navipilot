package com.jixiexiaoge.drivingassist.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.jixiexiaoge.drivingassist.data.LaneFusionEstimate
import com.jixiexiaoge.drivingassist.data.LaneFusionState
import com.jixiexiaoge.drivingassist.data.LaneEdgeEstimate
import com.jixiexiaoge.drivingassist.data.LaneGeometry
import com.jixiexiaoge.drivingassist.data.LaneGeometryStatus
import com.jixiexiaoge.drivingassist.ui.theme.NavipilotTheme
import org.junit.Rule
import org.junit.Test

class LaneFusionChipTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun oneSidedGeometryExplainsUnknownTotalsAndArConflictWithoutTruncation() {
        val geometry = LaneGeometry(LaneGeometryStatus.Stabilizing, widthM = 3.5f, rightSpaceM = 3.5f)
        val fusion = mutableStateOf(LaneFusionEstimate(LaneFusionState.VisionOnly,
            edge = LaneEdgeEstimate(geometry, null, 2, null, 2)))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                NavipilotTheme {
                    Box(Modifier.width(220.dp)) {
                        LaneFusionChip(laneFusionCompactText(fusion.value), false, compact = true, onVideo = true,
                            details = laneFusionText(fusion.value))
                    }
                }
            }
        }
        compose.onNodeWithText("几何估计：右起第 2 带 · AR右一致", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("ar-lane-chip").performClick()
        compose.onNodeWithTag("lane-fusion-details").assertIsDisplayed()
        compose.onNodeWithText("右缘参考：右起第 2 带 · 左起编号/总数未知", substring = true).assertIsDisplayed()
        compose.onNodeWithText("7000 本道宽 3.5m · 左/右余宽 未知/3.5m", substring = true).assertIsDisplayed()
        compose.onNodeWithText("AR 左待几何确认 / 右一致", substring = true).assertIsDisplayed()
        compose.runOnIdle {
            fusion.value = fusion.value.copy(edge = fusion.value.edge?.copy(arRight = 1))
        }
        compose.onNodeWithText("路缘与AR冲突 · 仅保留几何参考，车道号未知", substring = true).assertIsDisplayed()
        compose.onNodeWithText("AR 左待几何确认 / 右冲突", substring = true).assertIsDisplayed()
    }

    @Test
    fun estimateIsExplicitlyProvisionalAndConflictRemovesTheOldNumberAtLargeFont() {
        val fusion = mutableStateOf(LaneFusionEstimate(LaneFusionState.Estimated, count = 3, current = 2))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                NavipilotTheme {
                    Box(Modifier.width(220.dp)) {
                        LaneFusionChip(laneFusionText(fusion.value),
                            fusion.value.state != LaneFusionState.Estimated, compact = true, onVideo = true)
                    }
                }
            }
        }
        compose.onNodeWithText("融合估计：左起第 2 / 3 道 · 非定位确认").assertIsDisplayed()
        compose.runOnIdle { fusion.value = LaneFusionEstimate(LaneFusionState.Conflict) }
        compose.onNodeWithText("融合估计：左起第 2 / 3 道 · 非定位确认").assertDoesNotExist()
        compose.onNodeWithText("视觉与几何冲突 · 车道号未知").assertIsDisplayed()
        compose.runOnIdle { fusion.value = LaneFusionEstimate(LaneFusionState.Limited) }
        compose.onNodeWithText("模型计数范围未核准 · 车道号未知").assertIsDisplayed()
        compose.runOnIdle { fusion.value = LaneFusionEstimate(LaneFusionState.RoadTypeBlocked) }
        compose.onNodeWithText("当前道路未启用车道融合（仅高速/高架）").assertIsDisplayed()
        compose.onNodeWithTag("ar-lane-chip").assertIsDisplayed()
    }
}
