package com.jixiexiaoge.drivingassist.ui.components

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jixiexiaoge.drivingassist.data.buildDashboardSnapshot
import com.jixiexiaoge.drivingassist.ui.theme.NavipilotTheme
import fi.iki.elonen.NanoHTTPD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class RealTimeCompactLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun compactScrollSurvivesWideLayoutWithoutProjection() {
        val layoutWidth = mutableStateOf(340.dp)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1.5f)) {
                NavipilotTheme {
                    Box(Modifier.width(layoutWidth.value).height(760.dp)) {
                    RealTimePage(null, snapshot = buildDashboardSnapshot(null, 1000),
                        projection = MapProjectionViewport())
                    }
                }
            }
        }
        compose.onNodeWithTag("about-entry").performScrollTo()
        val offset = scrollPosition()
        compose.runOnIdle { layoutWidth.value = 740.dp }
        compose.waitUntil(5000) {
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag("compact-video-pane"))
                .fetchSemanticsNodes().isEmpty()
        }
        compose.runOnIdle { layoutWidth.value = 340.dp }
        compose.waitUntil(5000) { kotlin.math.abs(offset - scrollPosition()) <= 1f }
        compose.onNodeWithTag("about-entry").assertIsDisplayed()
    }

    @Test
    fun narrowScreenStacksMediaScrollsCardAndKeepsSameBrowserAcrossBackAndWideLayout() {
        val loads = AtomicInteger()
        val fixture = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response = when (session.uri) {
                "/health" -> newFixedLengthResponse(Response.Status.OK, "application/json",
                    """{"service":"navipilot-projection","schema_version":1,"running":true}""")
                else -> {
                    loads.incrementAndGet()
                    newFixedLengthResponse("<html>Compact map fixture</html>")
                }
            }
        }
        fixture.start()
        val layoutWidth = mutableStateOf(340.dp)
        val layoutHeight = mutableStateOf(760.dp)
        try {
            compose.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1.5f)) {
                    NavipilotTheme {
                        Box(Modifier.width(layoutWidth.value).height(layoutHeight.value)) {
                        RealTimePage(null, snapshot = buildDashboardSnapshot(null, 1000),
                            projection = MapProjectionViewport(fixture.listeningPort, 4f / 3f))
                        }
                    }
                }
            }
            compose.waitUntil(15000) { loads.get() > 0 }
            compose.onNodeWithTag("compact-video-pane").assertIsDisplayed()
            compose.onNodeWithTag("local-map-webview").assertIsDisplayed()
            val video = compose.onNodeWithTag("ar-video").fetchSemanticsNode().boundsInRoot
            val map = compose.onNodeWithTag("compact-map-anchor").fetchSemanticsNode().boundsInRoot
            val card = compose.onNodeWithTag("drive-view-card").fetchSemanticsNode().boundsInRoot
            assertTrue(video.bottom <= map.top)
            assertTrue(map.bottom <= card.top)
            assertTrue(map.width >= video.width * 0.95f)
            var browser: WebView? = null
            compose.runOnIdle { browser = findBrowser(compose.activity.window.decorView) }
            compose.onNodeWithTag("drive-view-gear").performScrollTo()
            val scroll = scrollPosition()
            compose.onNodeWithTag("drive-view-gear").performClick()
            compose.onNodeWithTag("map-fullscreen").assertIsDisplayed()
            compose.runOnIdle {
                assertSame(browser, findBrowser(compose.activity.window.decorView))
                compose.activity.onBackPressedDispatcher.onBackPressed()
            }
            compose.onNodeWithTag("drive-view-gear").assertIsDisplayed()
            assertEquals(scroll, scrollPosition(), 1f)
            compose.onNodeWithTag("about-entry").performScrollTo().assertIsDisplayed()
            val bottomScroll = scrollPosition()
            assertEquals(video, compose.onNodeWithTag("ar-video").fetchSemanticsNode().boundsInRoot)
            compose.onNodeWithTag("local-map-webview").assertIsNotDisplayed()
            compose.runOnIdle { layoutWidth.value = 740.dp }
            compose.onNodeWithTag("navi-map-row").assertIsDisplayed()
            compose.runOnIdle {
                assertSame(browser, findBrowser(compose.activity.window.decorView))
                layoutWidth.value = 340.dp
            }
            compose.waitUntil(5000) { kotlin.math.abs(bottomScroll - scrollPosition()) <= 1f }
            compose.onNodeWithTag("about-entry").assertIsDisplayed()
            compose.runOnIdle { assertSame(browser, findBrowser(compose.activity.window.decorView)) }
            compose.runOnIdle {
                layoutWidth.value = 740.dp
                layoutHeight.value = 340.dp
            }
            compose.onNodeWithTag("ar-video").assertDoesNotExist()
            compose.onNodeWithTag("navi-map-row").assertDoesNotExist()
            compose.onNodeWithTag("local-map-webview").assertIsNotDisplayed()
            compose.onNodeWithTag("drive-view-card").assertIsDisplayed()
            val page = compose.onNodeWithTag("realtime-page").fetchSemanticsNode().boundsInRoot
            val landscapeCard = compose.onNodeWithTag("drive-view-card").fetchSemanticsNode().boundsInRoot
            assertTrue(landscapeCard.top >= page.top && landscapeCard.bottom <= page.bottom)
            compose.onNodeWithTag("drive-view-gear").performClick()
            compose.onNodeWithTag("map-fullscreen").assertIsDisplayed()
            compose.onNodeWithTag("local-map-webview").assertIsDisplayed()
            compose.runOnIdle {
                assertSame(browser, findBrowser(compose.activity.window.decorView))
                compose.activity.onBackPressedDispatcher.onBackPressed()
            }
            compose.onNodeWithTag("drive-view-card").assertIsDisplayed()
            compose.onNodeWithTag("local-map-webview").assertIsNotDisplayed()
            compose.onNodeWithTag("ar-video").assertDoesNotExist()
            compose.runOnIdle {
                layoutHeight.value = 760.dp
                layoutWidth.value = 340.dp
            }
            compose.onNodeWithTag("compact-video-pane").assertIsDisplayed()
        } finally {
            fixture.stop()
        }
    }

    private fun scrollPosition(): Float = compose.onNodeWithTag("realtime-compact-scroll")
        .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun findBrowser(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) {
            repeat(view.childCount) { findBrowser(view.getChildAt(it))?.let { child -> return child } }
        }
        return null
    }
}
