package com.jixiexiaoge.drivingassist.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.jixiexiaoge.drivingassist.data.JetlinkPhase
import com.jixiexiaoge.drivingassist.data.JetlinkStatus
import com.jixiexiaoge.drivingassist.data.JetlinkStatusClient
import com.jixiexiaoge.drivingassist.data.buildDashboardSnapshot
import com.jixiexiaoge.drivingassist.ui.theme.NavipilotTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import androidx.lifecycle.Lifecycle
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class JetlinkBadgeTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun stalledRefreshExpiresReadyObservationBeforeResponseCompletes() {
        val calls = AtomicInteger()
        val release = CountDownLatch(1)
        var observed: JetlinkStatus? = null
        val client = JetlinkStatusClient(OkHttpClient.Builder().addInterceptor {
            if (calls.incrementAndGet() > 1) release.await(6, TimeUnit.SECONDS)
            Response.Builder().request(it.request()).protocol(Protocol.HTTP_1_1).code(200).message("test")
                .body("""{"jetlink":{"state":"ready","active":false,"fresh":true,
                    "severity":"ok","label":"MAC"}}""".toResponseBody()).build()
        }.build())
        try {
            compose.setContent {
                observed = rememberJetlinkStatus("127.0.0.1", client)
                Text(observed!!.phase.name)
            }
            compose.waitUntil(3_000) { observed?.phase == JetlinkPhase.Ready }
            compose.waitUntil(4_500) { calls.get() > 1 && observed?.phase == JetlinkPhase.Unavailable }
        } finally {
            release.countDown()
        }
    }

    @Test
    fun failedQueriesWithdrawReadyStateAndPollingStopsInBackground() {
        val count = AtomicInteger()
        val fail = AtomicBoolean(false)
        val host = mutableStateOf<String?>("127.0.0.1")
        var observed: JetlinkStatus? = null
        val client = JetlinkStatusClient(OkHttpClient.Builder().addInterceptor {
            count.incrementAndGet()
            val label = if (it.request().url.host == "127.0.0.1") "MAC" else "Android"
            Response.Builder().request(it.request()).protocol(Protocol.HTTP_1_1)
                .code(if (fail.get()) 503 else 200).message("test")
                .body("""{"jetlink":{"label":"$label","state":"ready","fresh":true,
                    "active":false,"severity":"ok"}}""".toResponseBody()).build()
        }.build())
        compose.setContent {
            observed = rememberJetlinkStatus(host.value, client)
            Text(observed!!.phase.name)
        }
        compose.waitUntil(3_000) { observed?.phase == JetlinkPhase.Ready }
        fail.set(true)
        compose.waitUntil(4_000) { observed?.phase == JetlinkPhase.Unavailable }
        compose.runOnIdle { host.value = null }
        compose.waitUntil { observed?.phase == JetlinkPhase.NoDevice }
        fail.set(false)
        compose.runOnIdle { host.value = "127.0.0.2" }
        compose.waitUntil(3_000) { observed?.label == "Android" && observed?.phase == JetlinkPhase.Ready }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        val backgroundCount = count.get()
        Thread.sleep(2_500)
        org.junit.Assert.assertEquals(backgroundCount, count.get())
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(3_000) { count.get() > backgroundCount }
    }

    @Test
    fun badgeTracksDevicePhaseAndDoesNotCoverSpeedAtLargeFont() {
        val status = mutableStateOf(JetlinkStatus("MAC", JetlinkPhase.Ready, "ok", true))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                NavipilotTheme {
                    Box(Modifier.width(430.dp)) {
                        DriveViewCard(buildDashboardSnapshot(null, 1000), jetlink = status.value)
                    }
                }
            }
        }
        compose.onNodeWithText("MAC READY").assertExists()
        val badge = compose.onNodeWithTag("jetlink-badge").fetchSemanticsNode().boundsInRoot
        val speed = compose.onNodeWithTag("drive-view-speed").fetchSemanticsNode().boundsInRoot
        val mode = compose.onNodeWithTag("drive-view-mode").fetchSemanticsNode().boundsInRoot
        assertTrue("Jetlink overlaps speed: $badge / $speed", badge.bottom <= speed.top)
        val badgeLayout = compose.onNodeWithTag("jetlink-badge").getUnclippedBoundsInRoot()
        val modeLayout = compose.onNodeWithTag("drive-view-mode").getUnclippedBoundsInRoot()
        assertTrue("Top badges are misaligned: $badgeLayout / $modeLayout",
            kotlin.math.abs((badgeLayout.top - modeLayout.top).value) <= 1f)
        assertTrue("Jetlink overlaps mode", badge.right < mode.left)
        compose.runOnIdle { status.value = JetlinkStatus("iOS", JetlinkPhase.Disconnected) }
        compose.onNodeWithText("iOS OFF").assertExists()
        compose.runOnIdle { status.value = JetlinkStatus("MAC", JetlinkPhase.Active, "unknown", false) }
        compose.onNodeWithText("MAC").assertExists()
        compose.onNodeWithText("!").assertDoesNotExist()
        compose.onNodeWithTag("jetlink-badge").performClick()
        compose.onNodeWithText("模型使用中").assertExists()
        compose.onNodeWithText("关闭").performClick()
        compose.runOnIdle { status.value = JetlinkStatus("MAC", JetlinkPhase.Active, "warning", true) }
        compose.onNodeWithText("MAC WARN").assertExists()
        compose.onNodeWithTag("jetlink-badge").performClick()
        compose.onNodeWithText("模型使用中\n警告").assertExists()
    }
}
