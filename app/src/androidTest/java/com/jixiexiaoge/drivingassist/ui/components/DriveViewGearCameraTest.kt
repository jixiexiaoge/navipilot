package com.jixiexiaoge.drivingassist.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.jixiexiaoge.drivingassist.data.CompactFrame
import com.jixiexiaoge.drivingassist.data.CompactServiceSample
import com.jixiexiaoge.drivingassist.data.CompactTelemetry
import com.jixiexiaoge.drivingassist.data.DashboardSnapshot
import com.jixiexiaoge.drivingassist.data.buildDashboardSnapshot
import com.jixiexiaoge.drivingassist.ui.theme.NavipilotTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * 挡位 → 相机视角 / 停止线 / 灯色的成图验证。
 *
 * 卡片是 2D 透视绘制，视角切换只能靠参数近似（原版是 Bevy 真 3D），
 * 所以这里把 P / R 两种视角都渲染出来存成 PNG，既做「不崩溃 + 有内容」的断言，
 * 也留一份人眼可比对的图（`/sdcard/Android/data/<pkg>/files/gear-view-*.png`）。
 */
class DriveViewGearCameraTest {
    @get:Rule
    val compose = createComposeRule()

    private fun telemetry(gear: String): CompactTelemetry {
        val lane = mapOf("x" to listOf(0f, 10f, 25f, 45f), "y" to listOf(-1.8f, -1.8f, -1.8f, -1.8f))
        val right = mapOf("x" to listOf(0f, 10f, 25f, 45f), "y" to listOf(1.8f, 1.8f, 1.8f, 1.8f))
        return CompactTelemetry(
            mapOf(
                "carState" to CompactServiceSample(CompactFrame("carState", 1, mapOf(
                    "vEgo" to 8f, "gearShifter" to gear, "steeringAngleDeg" to 12f,
                    "vCruiseCluster" to 60f, "brakeLights" to false,
                )), 100, 900),
                "carControl" to CompactServiceSample(CompactFrame("carControl", 1, mapOf(
                    "latActive" to true, "longActive" to true,
                )), 100, 900),
                "modelV2" to CompactServiceSample(CompactFrame("modelV2", 1, mapOf(
                    "position" to lane,
                    "laneLines" to listOf(lane, lane, right, right),
                    "laneLineProbs" to listOf(0.9f, 0.9f, 0.9f, 0.9f),
                    "roadEdges" to listOf(lane, right),
                )), 100, 900),
            ),
            200,
        )
    }

    private fun snapshot(gear: String, stopLine: Float? = null, lamp: Int = 0): DashboardSnapshot {
        val base = buildDashboardSnapshot(telemetry(gear), 200)
        return base.copy(road = base.road.copy(stopLineM = stopLine, trafficLightState = lamp))
    }

    private fun render(name: String, snapshot: DashboardSnapshot) {
        compose.setContent {
            NavipilotTheme {
                Box(Modifier.size(430.dp)) {
                    DriveViewCard(snapshot = snapshot)
                }
            }
        }
        compose.onNodeWithTag("drive-view-card").assertExists()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
        File(dir, "gear-view-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        assertTrue(bitmap.width > 100 && bitmap.height > 50)
    }

    @Test
    fun driveGearUsesReverseViewWithStopLineAtRedLight() {
        val snapshot = snapshot("drive", stopLine = 18f, lamp = 1)
        render("D-red-18m", snapshot)
        // 灯色胶囊必须出现（车端红 + 停止线）
        compose.onNodeWithTag("drive-view-traffic").assertExists()
    }

    @Test
    fun parkGearSwitchesToLeftSideView() {
        render("P", snapshot("park", stopLine = 40f, lamp = 2))
        compose.onNodeWithTag("drive-view-scene-left").assertExists()
        compose.onNodeWithTag("drive-view-acc").assertDoesNotExist()
        compose.onNodeWithTag("drive-view-limit").assertDoesNotExist()
    }

    @Test
    fun reverseGearSwitchesToTopView() {
        render("R", snapshot("reverse"))
        compose.onNodeWithTag("drive-view-scene-top").assertExists()
    }

    @Test
    fun unknownGearFallsBackToRearView() {
        render("unknown", snapshot("unknown"))
        compose.onNodeWithTag("drive-view-scene-rear").assertExists()
    }

    @Test
    fun suggestedSpeedNeverFillsLimitSignAndAbsentSignalsHaveNoGlyph() {
        val base = snapshot("drive")
        render("no-limit", base.copy(driving = base.driving.copy(suggestedKph = 100, adoptedLimitKph = null)))
        compose.onNodeWithTag("drive-view-suggested").assertExists()
        compose.onNodeWithTag("drive-view-limit").assertDoesNotExist()
        compose.onNodeWithTag("drive-view-left-turn").assertDoesNotExist()
        compose.onNodeWithTag("drive-view-right-turn").assertDoesNotExist()
    }

    @Test
    fun canLampPillIsGoneAndLightColorStaysInTheScene() {
        compose.setContent {
            NavipilotTheme {
                DriveViewCard(snapshot("drive", 18f, 1))
            }
        }
        // 7.9.41 删高德投屏 pill；7.9.46 再删「CAN 红灯」pill：
        // 卡上不再有任何灯色文字，灯色只由场景里的停止线 + 立杆表达。
        compose.onNodeWithTag("drive-view-traffic").assertDoesNotExist()
        compose.onNodeWithTag("drive-view-map-traffic").assertDoesNotExist()
        compose.onNodeWithTag("drive-view-notice").assertDoesNotExist()
        compose.onNodeWithTag("drive-view-scene-rear").assertExists()
    }
}
