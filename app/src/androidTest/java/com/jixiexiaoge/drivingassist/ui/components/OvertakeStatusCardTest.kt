package com.jixiexiaoge.drivingassist.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jixiexiaoge.drivingassist.data.CompactFrame
import com.jixiexiaoge.drivingassist.data.CompactTelemetryStore
import com.jixiexiaoge.drivingassist.data.toOvertakeDisplaySnapshot
import com.jixiexiaoge.drivingassist.ui.theme.NavipilotTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
import com.jixiexiaoge.drivingassist.MainActivityUIComponents
import com.jixiexiaoge.drivingassist.CarrotManFields
import com.jixiexiaoge.drivingassist.CarrotParamClient
import com.jixiexiaoge.drivingassist.ConditionalExperimentManager
import com.jixiexiaoge.drivingassist.data.AutomationObservation
import io.mockk.mockk
import io.mockk.verify
import io.mockk.Called

@RunWith(AndroidJUnit4::class)
class OvertakeStatusCardTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun aboutVersionUsesInstalledBuildWhileLauncherNameStaysStable() {
        compose.setContent {
            NavipilotTheme {
                MainActivityUIComponents.AboutDialog(compose.activity, aboutOpenTrigger = 1)
            }
        }
        compose.onNodeWithTag("about-app-version").assertExists()
        compose.onNodeWithText(
            "${com.jixiexiaoge.drivingassist.BuildConfig.VERSION_NAME} " +
                "(${com.jixiexiaoge.drivingassist.BuildConfig.VERSION_CODE})",
        ).assertExists()
        assertEquals("CP搭子", compose.activity.getString(com.jixiexiaoge.drivingassist.R.string.app_name))
    }

    @Test
    fun productionControlsSaveAllModesWithoutCommandsAndKeepManualRequestsSeparate() {
        val prefs = compose.activity.getSharedPreferences("CarrotAmap", 0)
        val previous = prefs.getInt("overtake_mode", 1)
        val leftKey = com.jixiexiaoge.drivingassist.navigation.LeftGuidancePolicy.PREFERENCE_KEY
        val previousLeft = if (prefs.contains(leftKey)) prefs.getBoolean(leftKey, true) else null
        prefs.edit().remove(leftKey).commit()
        prefs.edit().putInt("overtake_mode", 1).commit()
        val commands = mutableListOf<Pair<String, String>>()
        var observation by mutableStateOf(AutomationObservation.Disconnected)
        try {
            compose.setContent {
                NavipilotTheme {
                    Column(Modifier.requiredWidth(330.dp).verticalScroll(rememberScrollState())) {
                        MainActivityUIComponents.AdvancedFunctionsContent(
                            onSendCommand = { command, direction -> commands.add(command to direction) },
                            onSendRoadLimitSpeed = { error("Unexpected speed write") },
                            onLaunchAmap = {}, onSendNavConfirmation = {}, onPageChange = {},
                            isOpenpilotActive = true, carrotManFields = CarrotManFields(),
                            context = compose.activity, automationObservation = observation,
                        )
                    }
                }
            }
            compose.onNodeWithTag("left-guidance-filter").performScrollTo().assertIsOn().performClick()
            compose.runOnIdle { assertEquals(false, prefs.getBoolean(leftKey, true)) }
            compose.onNodeWithTag("left-guidance-filter").assertIsOff()
            compose.runOnIdle { prefs.edit().putBoolean(leftKey, true).apply() }
            compose.onNodeWithTag("left-guidance-filter").assertIsOn()
            compose.onNodeWithText(
                "默认开启；真实左出口也不传车辆。道路未知时继续拦截；手机提示与手动变道保留，不判断本车第几车道。",
            ).assertExists()
            listOf(2, 0, 1).forEach { mode ->
                compose.onNodeWithTag("home-control-3").performScrollTo().performClick()
                compose.waitUntil(3000) { prefs.getInt("overtake_mode", -1) == mode }
                compose.waitForIdle()
                // The button's debounce uses a coroutine delay.
                compose.mainClock.advanceTimeBy(400)
                compose.onNodeWithText(when (mode) {
                    0 -> "超车：关闭\n点击切换"
                    1 -> "超车：拨杆\n仅保存·未启用"
                    else -> "超车：自动\n仅保存·未启用"
                }).assertExists()
                compose.onNodeWithText(when (mode) {
                    2 -> "配置：自动超车"
                    0 -> "配置：禁止超车"
                    else -> "配置：拨杆超车"
                }).performScrollTo().assertExists()
                compose.onNodeWithText("App自动请求已暂停：compact缺少完整控制授权").assertExists()
            }
            assertTrue(commands.isEmpty())
            compose.runOnIdle { prefs.edit().putInt("overtake_mode", 0).apply() }
            compose.onNodeWithText("超车：关闭\n点击切换").assertExists()
            compose.runOnIdle { prefs.edit().putInt("overtake_mode", 2).apply() }
            compose.onNodeWithText("超车：自动\n仅保存·未启用").assertExists()
            listOf(
                AutomationObservation.Waiting to "等待7000必要服务数据",
                AutomationObservation.MissingAuthority to "7000观测新鲜，但缺少完整控制授权契约",
                AutomationObservation.Expired to "7000必要服务数据过期或时钟异常",
                AutomationObservation.Disconnected to "7000未连接",
            ).forEach { (status, label) ->
                compose.runOnIdle { observation = status }
                compose.onNodeWithText(label).performScrollTo().assertExists()
                compose.onNodeWithText("App自动请求已暂停：compact缺少完整控制授权").assertExists()
            }
            assertTrue(commands.isEmpty())
            compose.onNodeWithTag("home-control-4").performScrollTo().performClick()
            compose.onNodeWithTag("home-control-6").performScrollTo().performClick()
            compose.runOnIdle {
                assertEquals(listOf("LANECHANGE" to "LEFT", "LANECHANGE" to "RIGHT"), commands)
            }
        } finally {
            prefs.edit().putInt("overtake_mode", previous).commit()
            prefs.edit().apply {
                if (previousLeft == null) remove(leftKey) else putBoolean(leftKey, previousLeft)
            }.commit()
        }
    }

    @Test
    fun experimentalConfigurationSaveNeverWritesVehicleParametersEvenWithActiveFlag() {
        val prefs = compose.activity.getSharedPreferences("CarrotAmap", 0)
        val previous = prefs.getBoolean("cem_enabled", false)
        prefs.edit().putBoolean("cem_enabled", true).commit()
        val client = mockk<CarrotParamClient>(relaxed = true)
        val manager = mockk<ConditionalExperimentManager>(relaxed = true)
        val fields = mutableStateOf(CarrotManFields(active = true))
        try {
            compose.setContent {
                NavipilotTheme {
                    AutoSwitchExperimentPage(
                        onBack = {}, conditionalExperimentManager = manager, carrotParamClient = client,
                        carrotManFields = fields,
                        automationObservation = AutomationObservation.MissingAuthority,
                    )
                }
            }
            compose.onNodeWithText("条件实验：仅配置 · 自动切换未启用").assertExists()
            compose.onNodeWithText("已激活 — CEM可工作").assertDoesNotExist()
            compose.onNodeWithTag("cem-config-switch").performScrollTo().performClick()
            compose.onNodeWithText("💾 保存设置").performScrollTo().performClick()
            compose.onNodeWithText("确认保存").performClick()
            compose.runOnIdle {
                assertEquals(false, prefs.getBoolean("cem_enabled", true))
                verify { client wasNot Called }
                verify { manager wasNot Called }
            }
            compose.onNodeWithText("✓ 已保存 · 自动切换未启用").performScrollTo().assertExists()
        } finally {
            prefs.edit().putBoolean("cem_enabled", previous).commit()
        }
    }

    @Test
    fun wideObservationsShareRowAndLargeFontRestoresStackWithoutRemovingMetrics() {
        var scale by mutableStateOf(1f)
        val data = CompactTelemetryStore().snapshot(1000).toOvertakeDisplaySnapshot()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                NavipilotTheme {
                    Column(Modifier.requiredWidth(620.dp).verticalScroll(rememberScrollState())) {
                        OvertakeStatusCard(data, 2)
                    }
                }
            }
        }
        fun bounds(side: String) = compose.onNodeWithTag("overtake-$side").fetchSemanticsNode().boundsInRoot
        assertTrue(bounds("right").left >= bounds("left").right)
        assertTrue(kotlin.math.abs(bounds("left").top - bounds("right").top) < 1f)
        compose.runOnIdle { scale = 1.5f }
        compose.onNodeWithTag("overtake-right").performScrollTo()
        assertTrue(bounds("right").top > bounds("left").top)
        listOf("left", "right").forEach { side ->
            listOf("blindspot", "marking", "target", "distance", "relative", "probability").forEach {
                compose.onNodeWithTag("overtake-$side-$it").performScrollTo().assertExists()
            }
        }
        compose.onNodeWithText("本车绝对车道与变道完成未确认；本卡不发送命令").performScrollTo().assertExists()
    }

    @Test
    fun sideObservationsExpireIndependentlyAndLargeFontDoesNotHideUnknowns() {
        val store = CompactTelemetryStore()
        var telemetry by mutableStateOf(store.accept(listOf(
            CompactFrame("carState", 1, mapOf("leftLaneLine" to 21, "rightLaneLine" to 10,
                "leftBlindspot" to true, "rightBlindspot" to false)),
            CompactFrame("modelV2", 1, mapOf("frameId" to 1L,
                "laneLineProbs" to listOf(0.1f, 0.8f, 0.9f, 0.2f))),
            CompactFrame("radarState", 1, mapOf(
                "leadLeft" to mapOf("status" to true, "dRel" to 40f, "vRel" to -2f),
                "leadRight" to mapOf("status" to false))),
        ), 1000))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                NavipilotTheme {
                    Column(Modifier.requiredWidth(280.dp).verticalScroll(rememberScrollState())) {
                        OvertakeStatusCard(telemetry.toOvertakeDisplaySnapshot(), 2)
                    }
                }
            }
        }
        compose.onNodeWithText("配置：自动超车").assertExists()
        compose.onNodeWithText("App自动请求已暂停：compact缺少完整控制授权").performScrollTo().assertExists()
        compose.onNodeWithTag("overtake-left-marking").performScrollTo().assertExists()
        compose.onNodeWithText("黄色实线 · 原始码 21").assertExists()
        compose.onNodeWithTag("overtake-left-distance").performScrollTo().assertExists()
        compose.onNodeWithText("40.0 m").assertExists()
        val marking = compose.onNodeWithTag("overtake-left-marking").fetchSemanticsNode().boundsInRoot
        val blindspot = compose.onNodeWithTag("overtake-left-blindspot").fetchSemanticsNode().boundsInRoot
        assertTrue(marking.top > blindspot.bottom)
        compose.onNodeWithText("未检出（非通行确认）").performScrollTo().assertExists()
        compose.runOnIdle { telemetry = store.snapshot(1500) }
        compose.onNodeWithText("黄色实线 · 原始码 21").assertDoesNotExist()
        compose.onNodeWithTag("overtake-left-distance").performScrollTo()
        compose.onNodeWithText("40.0 m").assertExists()
        compose.runOnIdle { telemetry = store.snapshot(1750) }
        compose.onNodeWithText("40.0 m").assertDoesNotExist()
        compose.onNodeWithTag("overtake-right").performScrollTo().assertExists()
        compose.onNodeWithText("本车绝对车道与变道完成未确认；本卡不发送命令").performScrollTo().assertExists()
    }
}
