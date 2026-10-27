package com.jixiexiaoge.drivingassist.ui.components

import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jixiexiaoge.drivingassist.CarrotManFields
import com.jixiexiaoge.drivingassist.navigation.NaviSourceTracker
import com.jixiexiaoge.drivingassist.MainActivityUIComponents
import com.jixiexiaoge.drivingassist.NetworkManager
import com.jixiexiaoge.drivingassist.Page
import com.jixiexiaoge.drivingassist.ui.theme.NavipilotTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import io.mockk.every
import io.mockk.mockk

@RunWith(AndroidJUnit4::class)
class AdaptiveLayoutsInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun controlsRemainAvailableInTheirTabWhileAmapSamplingContinuesOffTab() {
        var samples = 0
        var destination: Page? = null
        val tracker = NaviSourceTracker()
        compose.setContent {
            val display = rememberAmapNavigationDisplay(
                CarrotManFields(), tracker, "AMAP", onSample = { samples++ },
            )
            NavipilotTheme {
                HomeDashboardTabs { section ->
                    if (section == 1) {
                        MainActivityUIComponents.AdvancedFunctionsContent(
                            onSendCommand = { _, _ -> },
                            onSendRoadLimitSpeed = {},
                            onLaunchAmap = {},
                            onSendNavConfirmation = {},
                            onPageChange = { destination = it },
                            isOpenpilotActive = false,
                            carrotManFields = CarrotManFields(),
                            context = compose.activity,
                        )
                    } else {
                        // 卡内分区按钮已删除，页面上的分区按钮才能切到控制页
                        Column {
                            OnPage()
                            Text("Dashboard section $section · ${display.state}")
                        }
                    }
                }
            }
        }
        compose.waitUntil(2_000) { samples >= 2 }
        val initialSamples = samples
        compose.onNodeWithTag("home-section-1").performClick()
        compose.onNodeWithTag("home-control-11").assertExists()
        compose.onNodeWithTag("home-control-14").assertExists()
        compose.onNodeWithTag("overtake-parameters").assertExists()
        compose.onNodeWithTag("home-control-11").performClick()
        compose.runOnIdle { assertEquals(Page.SmartSpeed, destination) }
        compose.onNodeWithTag("home-section-0").performClick()
        compose.waitUntil(2_000) { samples > initialSamples }
    }

    @Test
    fun sectionTabsSitAtThePageTopWhileTheTitleMovesToThePageBottom() {
        var contentStarts = 0
        var contentDisposals = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1f)) {
                NavipilotTheme {
                    HomeDashboardTabs(footer = { section ->
                        if (section == 0) {
                            Text("页面底部 $section", Modifier.testTag("test-footer-$section"))
                        } else {
                            SectionTitleFooter(section)
                        }
                    }) { section ->
                        DisposableEffect(section) {
                            contentStarts++
                            onDispose { contentDisposals++ }
                        }
                        // 实时页没有驾驶卡时，分区按钮退回页面左上角（真实实现在 RealTimePage 里）
                        if (section == 0) OnPage()
                        // 实时页（section 0）内部自带滚动容器，测试同样补一个，
                        // 其它页仍由 AdaptivePageColumn 提供滚动
                        val scrollable =
                            if (section == 0) Modifier.verticalScroll(rememberScrollState()) else Modifier
                        Column(scrollable) {
                            Text("Dashboard section $section")
                            Spacer(Modifier.height(900.dp))
                            Text("Bottom $section", Modifier.testTag("dashboard-bottom-$section"))
                        }
                    }
                }
            }
        }
        // 右侧 59dp 侧栏已删除：内容区从屏幕最左开始，宽度就是整屏宽度
        val content = bounds("home-section-content")
        assertEquals(0f, content.left, 1f)
        // 分区按钮在页面顶部；标题条下移到页面底部，顶部不再有大标题
        val tab = bounds("home-section-0")
        assertTrue("分区按钮必须在页面顶部", tab.top < content.top + content.height * 0.2f)
        assertTrue("分区按钮必须从左侧开始排", tab.left < content.left + content.width / 2f)
        val footer0 = bounds("test-footer-0")
        assertTrue("底部固定条必须贴页面底部", footer0.top > content.top + content.height * 0.7f)
        compose.onNodeWithTag("home-section-2").assertDoesNotExist()
        compose.onNodeWithTag("home-section-1").performClick()
        // 控制页的标题条现在是页面底部的一条
        val heading = bounds("home-section-heading-1")
        assertTrue("标题条必须在页面底部", heading.top > content.top + content.height * 0.7f)
        compose.onNodeWithTag("home-section-0").performClick()
        compose.onNodeWithText("Dashboard section 0").assertExists()
        compose.onNodeWithTag("dashboard-bottom-0").performScrollTo()
        compose.onNodeWithTag("home-section-1").performClick()
        compose.onNodeWithText("Dashboard section 1").assertExists()
        compose.onNodeWithTag("home-section-0").performClick()
        compose.onNodeWithTag("dashboard-bottom-0").assertExists()
        compose.runOnIdle {
            assertEquals(1, contentStarts)
            assertEquals(0, contentDisposals)
        }
    }

    @Test
    fun mapExpansionSpansBothColumnsAndReparentsTheSameStatefulContent() {
        var width by mutableStateOf(720.dp)
        var fontScale by mutableStateOf(1f)
        var mapStarts = 0
        var mapDisposals = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                NavipilotTheme {
                    AdaptiveHomeOverview(
                        modifier = Modifier.requiredWidth(width),
                        map = { mapModifier ->
                            DisposableEffect(Unit) {
                                mapStarts++
                                onDispose { mapDisposals++ }
                            }
                            Box(mapModifier.fillMaxWidth().aspectRatio(4f / 3f).testTag("map"))
                        },
                        navigation = { Box(Modifier.fillMaxWidth().height(40.dp).testTag("driving")) },
                        controls = {
                            var clicks by remember { mutableIntStateOf(0) }
                            Box(Modifier.fillMaxWidth().testTag("controls-layout")) {
                                Button(onClick = { clicks++ }, modifier = Modifier.fillMaxWidth().testTag("controls")) {
                                    Text("Controls $clicks")
                                }
                            }
                        },
                        results = { Box(Modifier.fillMaxWidth().height(40.dp).testTag("results")) },
                    )
                }
            }
        }
        val original = bounds("map")
        compose.onNodeWithTag("controls").performClick()
        compose.onNodeWithTag("map").performClick()
        val enlarged = bounds("map")
        assertTrue(enlarged.width > original.width * 1.9f)
        assertEquals(bounds("driving").left, enlarged.left, 1f)
        assertEquals(bounds("controls").right, enlarged.right, 1f)
        assertTrue(bounds("driving").top > enlarged.bottom)
        assertTrue(bounds("controls").top > enlarged.bottom)
        assertTrue(bounds("results").top >= bounds("controls").bottom)
        assertEquals(enlarged.width / enlarged.height, 4f / 3f, 0.01f)
        compose.onNodeWithText("Controls 1").assertExists()
        compose.onNodeWithTag("map").performClick()
        assertEquals(original.width, bounds("map").width, 1f)
        assertEquals(bounds("map").top, bounds("controls-layout").top, 1f)
        compose.onNodeWithTag("map").performClick()
        compose.runOnIdle { width = 360.dp }
        compose.onNodeWithTag("map").assertHasNoClickAction()
        assertTrue(bounds("controls").top >= bounds("results").bottom)
        compose.runOnIdle { width = 720.dp }
        assertEquals(original.width, bounds("map").width, 1f)
        compose.onNodeWithTag("map").performClick()
        compose.runOnIdle { fontScale = 1.4f }
        compose.onNodeWithTag("map").assertHasNoClickAction()
        assertTrue(bounds("controls").top >= bounds("results").bottom)
        compose.runOnIdle { fontScale = 1f }
        assertEquals(original.width, bounds("map").width, 1f)
        compose.onNodeWithText("Controls 1").assertExists()
        compose.runOnIdle {
            assertEquals(1, mapStarts)
            assertEquals(0, mapDisposals)
        }
    }

    @Test
    fun expandedMapUsesAvailableDashboardWidthWithoutBypassingContentCap() {
        compose.setContent {
            NavipilotTheme {
                AdaptivePageColumn(Modifier.requiredWidth(1400.dp)) {
                    AdaptiveHomeOverview(
                        map = { modifier -> Box(modifier.fillMaxWidth().height(40.dp).testTag("map")) },
                        navigation = { Box(Modifier.fillMaxWidth().height(20.dp).testTag("driving")) },
                        controls = { Box(Modifier.fillMaxWidth().height(20.dp).testTag("controls")) },
                        results = { Box(Modifier.fillMaxWidth().height(20.dp).testTag("results")) },
                    )
                }
            }
        }
        compose.onNodeWithTag("map").performClick()
        val map = bounds("map")
        val cappedContentWidth = with(compose.density) { (1040.dp - 24.dp).toPx() }
        assertEquals(cappedContentWidth, map.width, 1f)
        assertEquals(bounds("driving").left, map.left, 1f)
        assertEquals(bounds("controls").right, map.right, 1f)
        assertTrue(bounds("driving").top > map.bottom)
        assertTrue(bounds("controls").top > map.bottom)
    }

    @Test
    fun foldingReflowsEveryCardWithoutRestartingContentOrLosingState() {
        var width by mutableStateOf(720.dp)
        var sourceLabel by mutableStateOf("Initial")
        var previewStarts = 0
        var controlStarts = 0
        compose.setContent {
            NavipilotTheme {
                AdaptiveHomeOverview(
                    modifier = Modifier.requiredWidth(width),
                    map = {
                        DisposableEffect(Unit) {
                            previewStarts++
                            onDispose { }
                        }
                        Box(Modifier.fillMaxWidth().height(120.dp).testTag("map"))
                    },
                    navigation = {
                        var clicks by remember { mutableIntStateOf(0) }
                        Button(onClick = { clicks++ }, modifier = Modifier.fillMaxWidth().testTag("driving")) {
                            Text("$sourceLabel $clicks")
                        }
                    },
                    results = { Box(Modifier.fillMaxWidth().height(80.dp).testTag("results")) },
                    controls = {
                        DisposableEffect(Unit) {
                            controlStarts++
                            onDispose { }
                        }
                        var clicks by remember { mutableIntStateOf(0) }
                        Box(Modifier.fillMaxWidth().testTag("controls-layout")) {
                            Button(onClick = { clicks++ }, modifier = Modifier.fillMaxWidth().testTag("controls")) {
                                Text("Controls $clicks")
                            }
                        }
                    },
                )
            }
        }
        val expandedMap = bounds("map")
        val expandedDriving = bounds("driving")
        assertTrue(expandedDriving.top >= expandedMap.bottom)
        assertTrue(bounds("results").left > expandedMap.right)
        assertTrue(bounds("results").top >= bounds("controls").bottom)
        assertTrue(bounds("controls").left > expandedMap.right)
        assertEquals(expandedMap.top, bounds("controls-layout").top, 1f)
        compose.runOnIdle { width = 360.dp; sourceLabel = "Updated" }
        compose.onNodeWithTag("driving").performClick()
        compose.onNodeWithText("Updated 1").assertExists()
        compose.onNodeWithTag("controls").performClick()
        compose.onNodeWithText("Controls 1").assertExists()
        assertTrue(bounds("driving").top >= bounds("map").bottom)
        assertTrue(bounds("results").top >= bounds("driving").bottom)
        assertTrue(bounds("controls").top >= bounds("results").bottom)

        compose.runOnIdle { width = 720.dp }
        compose.onNodeWithText("Updated 1").assertExists()
        compose.onNodeWithText("Controls 1").assertExists()
        assertTrue(bounds("controls").left > bounds("map").right)
        assertTrue(bounds("results").top >= bounds("controls").bottom)
        assertTrue(bounds("results").left > bounds("map").right)
        assertTrue(bounds("driving").left < bounds("controls").left)
        compose.runOnIdle {
            assertEquals(1, previewStarts)
            assertEquals(1, controlStarts)
        }
    }

    @Test
    fun largeFontsUseSingleColumnEvenWhenTheWindowIsWide() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.4f)) {
                AdaptiveHomeOverview(
                    modifier = Modifier.requiredWidth(720.dp),
                    map = { Box(Modifier.fillMaxWidth().height(120.dp).testTag("map")) },
                    navigation = { Box(Modifier.fillMaxWidth().height(80.dp).testTag("driving")) },
                    results = { Box(Modifier.fillMaxWidth().height(80.dp).testTag("results")) },
                    controls = { Box(Modifier.fillMaxWidth().height(80.dp).testTag("controls")) },
                )
            }
        }
        assertTrue(bounds("driving").top >= bounds("map").bottom)
        assertTrue(bounds("results").top >= bounds("driving").bottom)
        assertTrue(bounds("controls").top >= bounds("results").bottom)
    }

    @Test
    fun allNineControlsKeepTheirOrderSizeAndPageActionsAcrossWidths() {
        var width by mutableStateOf(360.dp)
        var page: Page? = null
        compose.setContent {
            val context = LocalContext.current
            NavipilotTheme {
                AdaptiveHomeOverview(
                    modifier = Modifier.requiredWidth(width),
                    map = { Box(Modifier.fillMaxWidth().height(40.dp).testTag("map")) },
                    navigation = { Box(Modifier.fillMaxWidth().height(40.dp).testTag("driving")) },
                    results = { Box(Modifier.fillMaxWidth().height(40.dp).testTag("results")) },
                    controls = { MainActivityUIComponents.AdvancedFunctionsContent(
                        onSendCommand = { _, _ -> },
                        onSendRoadLimitSpeed = {},
                        onLaunchAmap = {},
                        onSendNavConfirmation = {},
                        onPageChange = { page = it },
                        isOpenpilotActive = false,
                        carrotManFields = CarrotManFields(),
                        context = context,
                    ) },
                )
            }
        }
        assertControlGrid()
        compose.onNodeWithTag("overtake-parameters").assertExists()
        compose.onNodeWithText("最小超车速度").assertExists()
        compose.onNodeWithText("速度差阈值").assertExists()
        compose.onNodeWithTag("home-control-11").performClick()
        compose.runOnIdle { assertEquals(Page.SmartSpeed, page); width = 720.dp }
        assertControlGrid()
        compose.onNodeWithTag("overtake-parameters").assertExists()
        assertTrue(bounds("home-control-3").left > bounds("map").right)
        compose.runOnIdle { width = 360.dp }
        compose.onNodeWithTag("home-control-14").performClick()
        compose.runOnIdle { assertEquals(Page.Projection, page) }
        compose.onNodeWithTag("home-control-9").performClick()
        compose.runOnIdle { assertEquals(Page.Experiment, page) }
    }

    @Test
    fun cruiseOpensSelectedDeviceWebPageAndDoesNotLaunchWithoutDevice() {
        var deviceIp: String? = null
        val launches = mutableListOf<Intent>()
        val network = mockk<NetworkManager>()
        every { network.getCurrentDeviceIP() } answers { deviceIp }
        val context = object : ContextWrapper(compose.activity) {
            override fun startActivity(intent: Intent) {
                launches.add(intent)
            }
        }
        compose.setContent {
            NavipilotTheme {
                MainActivityUIComponents.AdvancedFunctionsContent(
                    onSendCommand = { _, _ -> },
                    onSendRoadLimitSpeed = {},
                    onLaunchAmap = {},
                    onSendNavConfirmation = {},
                    onPageChange = {},
                    isOpenpilotActive = false,
                    carrotManFields = CarrotManFields(),
                    networkManager = network,
                    context = context,
                )
            }
        }
        compose.onNodeWithTag("home-control-12").performClick()
        compose.runOnIdle {
            assertTrue(launches.isEmpty())
            deviceIp = "192.168.50.10"
        }
        compose.onNodeWithTag("home-control-12").performClick()
        compose.runOnIdle {
            assertEquals(1, launches.size)
            assertEquals(Intent.ACTION_VIEW, launches.single().action)
            assertEquals("http://192.168.50.10:7000", launches.single().dataString)
            assertTrue(launches.single().flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        }
    }

    private fun assertControlGrid() {
        val ids = listOf(3, 4, 6, 11, 12, 13, 9, 14, 15)
        val minimumTouchPx = with(compose.density) { 48.dp.toPx() }
        ids.chunked(3).forEach { row ->
            val cells = row.map { bounds("home-control-$it") }
            cells.forEach {
                assertTrue(it.width >= minimumTouchPx)
                assertTrue(it.height >= minimumTouchPx)
                assertTrue(abs(it.width - cells.first().width) <= 1f)
                assertTrue(abs(it.top - cells.first().top) <= 1f)
                assertTrue(abs(it.height - cells.first().height) <= 1f)
            }
            assertTrue(cells[1].left > cells[0].right)
            assertTrue(cells[2].left > cells[1].right)
        }
        assertTrue(bounds("home-control-11").top > bounds("home-control-3").bottom)
        assertTrue(bounds("home-control-9").top > bounds("home-control-11").bottom)
    }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
}
