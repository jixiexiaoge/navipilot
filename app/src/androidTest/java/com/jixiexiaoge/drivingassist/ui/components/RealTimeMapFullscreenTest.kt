package com.jixiexiaoge.drivingassist.ui.components

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import com.jixiexiaoge.drivingassist.CarrotManFields
import com.jixiexiaoge.drivingassist.data.buildDashboardSnapshot
import com.jixiexiaoge.drivingassist.navigation.AmapDisplayState
import com.jixiexiaoge.drivingassist.navigation.NaviSourceTracker
import com.jixiexiaoge.drivingassist.navigation.buildAmapNavigationDisplay
import com.jixiexiaoge.drivingassist.ui.theme.NavipilotTheme as AppTheme
import com.navipilot.amap2tesla.ProjectionState
import fi.iki.elonen.NanoHTTPD
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.webrtc.SurfaceViewRenderer

@androidx.compose.runtime.Composable
private fun NavipilotTheme(content: @androidx.compose.runtime.Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(1.5f),
    ) {
        androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier.width(740.dp),
        ) { AppTheme(content = content) }
    }
}

class RealTimeMapFullscreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()
    private var server: NanoHTTPD? = null
    private val originalPort = ProjectionState.serverPort
    private val originalRunning = ProjectionState.running
    private val originalWidth = ProjectionState.width
    private val originalHeight = ProjectionState.height

    @After
    fun tearDown() {
        compose.runOnUiThread {
            ProjectionState.serverPort = originalPort
            ProjectionState.running = originalRunning
            ProjectionState.width = originalWidth
            ProjectionState.height = originalHeight
        }
        server?.stop()
    }

    @Test
    fun gearExpandsSameBrowserAndBackRestoresLayoutAndScrollWithoutResizingProjection() {
        val pages = AtomicInteger()
        val fixture = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response = when (session.uri) {
                "/health" -> newFixedLengthResponse(
                    Response.Status.OK, "application/json",
                    """{"service":"navipilot-projection","schema_version":1,"running":true}""",
                )
                "/" -> {
                    pages.incrementAndGet()
                    newFixedLengthResponse(Response.Status.OK, "text/html", "<html>Projection fixture</html>")
                }
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
            }
        }
        server = fixture
        fixture.start()
        compose.runOnUiThread {
            ProjectionState.serverPort = fixture.listeningPort
            ProjectionState.running = true
            ProjectionState.width = 960
            ProjectionState.height = 720
        }
        var parentBacks = 0
        val amap = buildAmapNavigationDisplay(
            CarrotManFields(), NaviSourceTracker().snapshot(), "AMAP", 1000,
        ).copy(state = AmapDisplayState.Fresh)
        compose.setContent {
            NavipilotTheme {
                BackHandler { parentBacks++ }
                RealTimePage(null, snapshot = buildDashboardSnapshot(null, 1000), amap = amap)
            }
        }
        compose.waitUntil(15_000) { pages.get() == 1 }
        compose.onNodeWithTag("local-map-webview").assertIsDisplayed()
        val browser = findView(WebView::class.java)
        assertNotNull(browser)
        val renderer = findView(SurfaceViewRenderer::class.java)
        assertNotNull(renderer)
        val mapBounds = compose.onNodeWithTag("local-map-browser").getUnclippedBoundsInRoot()
        compose.onNodeWithTag("about-entry").performScrollTo()
        val scroll = detailsScroll()

        repeat(3) { cycle ->
            compose.onNodeWithTag("drive-view-gear").performClick()
            compose.onNodeWithTag("map-fullscreen").assertIsDisplayed()
            assertEquals(
                compose.onNodeWithTag("realtime-page").getUnclippedBoundsInRoot(),
                compose.onNodeWithTag("local-map-browser").getUnclippedBoundsInRoot(),
            )
            compose.onNodeWithTag("drive-view-card").assertDoesNotExist()
            compose.runOnIdle {
                assertSame(browser, findView(WebView::class.java))
                assertSame(renderer, findView(SurfaceViewRenderer::class.java))
                assertEquals(View.INVISIBLE, renderer?.visibility)
                assertEquals(960, ProjectionState.width)
                assertEquals(720, ProjectionState.height)
            }
            if (cycle == 0) {
                compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            } else {
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            }
            compose.onNodeWithTag("map-fullscreen").assertDoesNotExist()
            compose.onNodeWithTag("drive-view-card").assertIsDisplayed()
            assertEquals(mapBounds, compose.onNodeWithTag("local-map-browser").getUnclippedBoundsInRoot())
            assertEquals(scroll, detailsScroll(), 1f)
            compose.runOnIdle {
                assertSame(browser, findView(WebView::class.java))
                assertEquals(View.VISIBLE, renderer?.visibility)
                assertEquals(0, parentBacks)
                assertEquals(1, pages.get())
            }
        }
        compose.runOnIdle {
            compose.activity.onBackPressedDispatcher.onBackPressed()
            assertEquals(1, parentBacks)
        }
    }

    @Test
    fun unavailableMapCanExpandAndReturnWithoutOpeningProjectionAutomatically() {
        var opens = 0
        val amap = buildAmapNavigationDisplay(
            CarrotManFields(), NaviSourceTracker().snapshot(), "AMAP", 1000,
        )
        compose.setContent {
            NavipilotTheme {
                RealTimePage(null, snapshot = buildDashboardSnapshot(null, 1000), amap = amap,
                    onOpenProjection = { opens++ })
            }
        }
        compose.onNodeWithTag("drive-view-gear").performClick()
        compose.onNodeWithTag("map-fullscreen").assertIsDisplayed()
        compose.onNodeWithTag("map-browser-start-amap").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(0, opens)
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.onNodeWithTag("drive-view-gear").assertIsDisplayed()
        assertTrue(opens == 0)
    }

    @Test
    fun loadingFullscreenMapCannotClickHiddenControlsThroughTheOverlay() {
        val fixture = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                Thread.sleep(2_000)
                return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "waiting")
            }
        }
        server = fixture
        fixture.start()
        compose.runOnUiThread {
            ProjectionState.serverPort = fixture.listeningPort
            ProjectionState.running = true
        }
        val amap = buildAmapNavigationDisplay(
            CarrotManFields(), NaviSourceTracker().snapshot(), "AMAP", 1000,
        ).copy(state = AmapDisplayState.Fresh)
        compose.setContent {
            NavipilotTheme {
                HomeDashboardTabs { section ->
                    if (section == 0) {
                        RealTimePage(null, snapshot = buildDashboardSnapshot(null, 1000), amap = amap)
                    } else {
                        Text("Controls")
                    }
                }
            }
        }
        val wheel = compose.onNodeWithTag("drive-view-wheel").fetchSemanticsNode().boundsInRoot.center
        compose.onNodeWithTag("drive-view-gear").performClick()
        compose.onNodeWithTag("map-browser-probing").assertIsDisplayed()
        val full = compose.onNodeWithTag("map-fullscreen").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("map-fullscreen").performTouchInput { click(wheel - full.topLeft) }
        compose.onNodeWithText("Controls").assertDoesNotExist()
        compose.runOnIdle {
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.onNodeWithTag("drive-view-wheel").performClick()
        compose.onNodeWithText("Controls").assertIsDisplayed()
    }

    private fun detailsScroll(): Float = compose.onNodeWithTag("realtime-details")
        .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun <T : View> findView(type: Class<T>): T? {
        fun find(view: View): T? {
            if (type.isInstance(view)) return type.cast(view)
            if (view is ViewGroup) {
                repeat(view.childCount) { find(view.getChildAt(it))?.let { child -> return child } }
            }
            return null
        }
        var result: T? = null
        compose.runOnUiThread { result = find(compose.activity.window.decorView) }
        return result
    }
}
