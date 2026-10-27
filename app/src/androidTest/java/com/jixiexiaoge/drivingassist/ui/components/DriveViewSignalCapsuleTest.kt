package com.jixiexiaoge.drivingassist.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.jixiexiaoge.drivingassist.data.CompactFrame
import com.jixiexiaoge.drivingassist.data.CompactServiceSample
import com.jixiexiaoge.drivingassist.data.CompactTelemetry
import com.jixiexiaoge.drivingassist.data.DashboardSnapshot
import com.jixiexiaoge.drivingassist.data.buildDashboardSnapshot
import com.jixiexiaoge.drivingassist.navigation.AmapDisplaySignal
import com.jixiexiaoge.drivingassist.ui.theme.NavipilotTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot

/**
 * 高德红绿灯胶囊（7.9.59）：驾驶卡右侧的 `[倒数] [●方向]`。
 *
 * 断言三件事：
 * 1. 传 [AmapDisplaySignal] 时胶囊与倒数数字出现；
 * 2. 不传时整颗胶囊不存在（不占位、不挡限速牌/设备角标）；
 * 3. 渲染成图留档（`/sdcard/Android/data/<pkg>/files/signal-capsule.png`），人眼可比对高德截图。
 */
class DriveViewSignalCapsuleTest {
    @get:Rule
    val compose = createComposeRule()

    private fun snapshot(): DashboardSnapshot {
        val lane = mapOf("x" to listOf(0f, 10f, 25f, 45f), "y" to listOf(-1.8f, -1.8f, -1.8f, -1.8f))
        val right = mapOf("x" to listOf(0f, 10f, 25f, 45f), "y" to listOf(1.8f, 1.8f, 1.8f, 1.8f))
        val telemetry = CompactTelemetry(
            mapOf(
                "carState" to CompactServiceSample(CompactFrame("carState", 1, mapOf(
                    "vEgo" to 8f, "gearShifter" to "drive", "steeringAngleDeg" to 0f,
                    "vCruiseCluster" to 60f, "brakeLights" to false,
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
        return buildDashboardSnapshot(telemetry, 200)
    }

    private fun render(name: String, signal: AmapDisplaySignal?) {
        compose.setContent {
            NavipilotTheme {
                Box(Modifier.size(430.dp)) {
                    DriveViewCard(snapshot = snapshot(), amapSignal = signal)
                }
            }
        }
        compose.onNodeWithTag("drive-view-card").assertExists()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
        File(dir, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        assertTrue(bitmap.width > 100 && bitmap.height > 50)
    }

    @Test
    fun capsuleShowsCountdownAndArrowWhenSignalPresent() {
        render("signal-capsule-red", AmapDisplaySignal(state = 1, direction = 4, remainingSec = 9))
        compose.onNodeWithTag("drive-view-signal").assertExists()
        compose.onNodeWithTag("drive-view-signal-countdown").assertExists()
        compose.onNodeWithTag("drive-view-signal-arrow").assertExists()
    }

    @Test
    fun capsuleAbsentWithoutSignal() {
        render("signal-capsule-none", null)
        compose.onNodeWithTag("drive-view-signal").assertDoesNotExist()
        compose.onNodeWithTag("drive-view-signal-countdown").assertDoesNotExist()
    }

    @Test
    fun unknownDirectionDoesNotDrawStraightArrow() {
        render("signal-capsule-unknown", AmapDisplaySignal(1, 0, 9))
        compose.onNodeWithTag("drive-view-signal-unknown").assertExists()
        compose.onNodeWithTag("drive-view-signal-arrow").assertDoesNotExist()
    }

    @Test
    fun endedCountdownDoesNotKeepOldPhaseVisible() {
        render("signal-capsule-ended", AmapDisplaySignal(1, 4, 0))
        compose.onNodeWithTag("drive-view-signal").assertDoesNotExist()
    }

    @Test
    fun unsupportedPhaseDoesNotDefaultToGreen() {
        render("signal-capsule-invalid", AmapDisplaySignal(3, 4, 9))
        compose.onNodeWithTag("drive-view-signal").assertDoesNotExist()
    }
}
