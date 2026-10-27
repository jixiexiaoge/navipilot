package com.jixiexiaoge.drivingassist.ui.components

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.jixiexiaoge.drivingassist.CarrotManFields
import com.jixiexiaoge.drivingassist.data.buildDashboardSnapshot
import com.jixiexiaoge.drivingassist.navigation.AmapJunctionLane
import com.jixiexiaoge.drivingassist.navigation.AmapJunctionLanes
import com.jixiexiaoge.drivingassist.navigation.AmapLaneDirection
import com.jixiexiaoge.drivingassist.navigation.AmapNavigationDisplay
import com.jixiexiaoge.drivingassist.navigation.AmapDisplayAction
import com.jixiexiaoge.drivingassist.navigation.AmapDisplayState
import com.jixiexiaoge.drivingassist.navigation.NaviSourceTracker
import com.jixiexiaoge.drivingassist.navigation.buildAmapNavigationDisplay
import androidx.compose.ui.unit.dp
import com.jixiexiaoge.drivingassist.ui.theme.NavipilotTheme as AppTheme
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.JavaI420Buffer
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoFrame
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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

class RealTimePageTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()
    private var fixture: WebRtcFixture? = null

    /** 空态高德数据：既没有车道组、也没有当前动作，右格应该退回前视画面 */
    private fun quietAmap(): AmapNavigationDisplay = buildAmapNavigationDisplay(
        CarrotManFields(), NaviSourceTracker().snapshot(), "AMAP", 1000,
    )

    @After
    fun tearDown() {
        fixture?.close()
    }

    @Test
    fun connectedIpBadgeOpensDebugEvenWithoutCameraVideo() {
        val opened = mutableStateOf(false)
        compose.setContent {
            NavipilotTheme {
                RealTimePage(
                    host = null,
                    deviceIp = "192.168.1.25",
                    connection = com.jixiexiaoge.drivingassist.data.CommaConnectionDisplay.Compact,
                    snapshot = buildDashboardSnapshot(null, 1000),
                    onConnectionClick = { opened.value = true },
                )
                if (opened.value) {
                    Carrot7706JsonDebugOverlay(
                        fields = CarrotManFields(), networkClient = null,
                        onDismiss = { opened.value = false },
                    )
                }
            }
        }
        compose.onNodeWithTag("drive-view-camera-ip", useUnmergedTree = true).performTouchInput { click() }
        compose.onNodeWithText("7706 UDP").assertExists()
    }

    @Test
    fun noSelectedDeviceShowsWebRtcPlaceholderFillingVideoArea() {
        compose.setContent {
            NavipilotTheme { RealTimePage(null) }
        }

        compose.onNodeWithText("请先连接 comma 设备").assertIsDisplayed()
        // 7.9.53：顶部状态行已删除，信息并进驾驶卡右下角角标；没有看板数据时连角标也不该存在
        compose.onNodeWithTag("camera-status").assertDoesNotExist()
        compose.onNodeWithTag("drive-view-camera").assertDoesNotExist()
        val videoBounds = compose.onNodeWithTag("ar-video").fetchSemanticsNode().boundsInRoot
        val surfaceBounds = compose.onNodeWithTag("ar-video-surface").fetchSemanticsNode().boundsInRoot
        assertTrue(surfaceBounds.height > 0)
        assertTrue(surfaceBounds == videoBounds)
    }

    /**
     * 7.9.52 版式约束：前视画面从驾驶卡里剥离，成为「路口图那一格」的双通道之一。
     *
     * 没有路口引导（无车道组、无当前动作）时：右半格必须是画面，宽度≈半屏、与左格等高，
     * 且驾驶卡里不能再出现画中画。
     */
    @Test
    fun cameraFillsRightHalfPaneWhenThereIsNoJunctionGuidance() {
        compose.setContent {
            NavipilotTheme {
                RealTimePage(
                    host = null,
                    snapshot = buildDashboardSnapshot(null, 1000),
                    amap = quietAmap(),
                )
            }
        }

        compose.onNodeWithTag("realtime-page").assertIsDisplayed()
        val row = compose.onNodeWithTag("navi-map-row").getUnclippedBoundsInRoot()
        val video = compose.onNodeWithTag("ar-video").getUnclippedBoundsInRoot()
        val driving = compose.onNodeWithTag("drive-view-card").getUnclippedBoundsInRoot()
        assertTrue("地图/视频行应在驾驶卡上方", row.bottom <= driving.top)

        val map = compose.onNodeWithTag("local-map-browser").getUnclippedBoundsInRoot()
        val widthRatio = (video.right - video.left) /
            ((video.right - video.left) + (map.right - map.left))
        assertTrue("视频按实际比例分配宽度，实际 $widthRatio", widthRatio in 0.50f..0.60f)
        assertTrue("画面应落在地图右侧", video.left >= map.right)
        val mapRatio = (map.right - map.left).value / (map.bottom - map.top).value
        assertTrue("地图保持完整4:3比例，实际 $mapRatio", kotlin.math.abs(mapRatio - 4f / 3f) < 0.02f)
        assertTrue(
            "画面应撑满格高：video=${video.bottom - video.top} row=${row.bottom - row.top}",
            video.bottom - video.top > (row.bottom - row.top) * 0.9f,
        )
        // 画中画已经从驾驶卡剥离，卡片里不该再有它
        compose.onNodeWithTag("drive-view-pip").assertDoesNotExist()
    }

    @Test
    fun receivedAmapLampShowsWithoutNavigationOrReadyRouteAndExpires() {
        val fields = CarrotManFields()
        val sources = NaviSourceTracker()
        sources.updateNavigationSignal(1, 4, 12, fields.noaRouteGeneration, 1000)
        val display = mutableStateOf(buildAmapNavigationDisplay(fields, sources.snapshot(), "AMAP", 1100))
        compose.setContent {
            NavipilotTheme {
                RealTimePage(
                    host = null, snapshot = buildDashboardSnapshot(null, 1000),
                    amap = display.value, routeReady = false,
                )
            }
        }
        compose.onNodeWithTag("drive-view-signal").assertExists()
        compose.onNodeWithTag("drive-view-signal-countdown").assertExists()
        compose.runOnIdle {
            display.value = buildAmapNavigationDisplay(fields, sources.snapshot(), "AMAP", 12000)
        }
        compose.onNodeWithTag("drive-view-signal").assertDoesNotExist()
    }

    /**
     * 7.9.64：路口放大图已下线 —— 即便高德给了完整的路口车道与当前动作，
     * 右格也必须**始终**是 5001 的实时画面（以前这时会被路口图占掉、画面整个消失）。
     */
    @Test
    fun roadCameraPaneSurvivesFullJunctionGuidance() {
        val display = mutableStateOf(quietAmap().copy(
            state = AmapDisplayState.Fresh,
            current = AmapDisplayAction("右转", "出口", 80, maneuverIcon = 3),
            junction = AmapJunctionLanes(
                lanes = listOf(
                    AmapJunctionLane(1, AmapLaneDirection("↑", "直行"), false, "0"),
                    AmapJunctionLane(2, AmapLaneDirection("←", "左转"), true, "1"),
                ),
                leftToRightKnown = true,
                actionDistanceM = 30,
            ),
        ))
        compose.setContent {
            NavipilotTheme {
                RealTimePage(
                    host = null, snapshot = buildDashboardSnapshot(null, 1000),
                    amap = display.value, routeReady = true,
                )
            }
        }
        compose.onNodeWithTag("junction-cross-view").assertDoesNotExist()
        compose.onNodeWithTag("ar-video").assertExists()
        // 路线任务挂起 / 广播过期：画面依然在，不会被别的卡片顶掉
        compose.runOnIdle { display.value = quietAmap() }
        compose.onNodeWithTag("ar-video").assertExists()
        compose.onNodeWithTag("junction-cross-view").assertDoesNotExist()
    }

    @Test
    fun leavingArDisposesRendererAndReturningCreatesPageAgain() {
        val host = mutableStateOf<String?>(null)
        compose.setContent {
            NavipilotTheme {
                HomeDashboardTabs { section ->
                    if (section == 0) RealTimePage(host.value) else Text("Section $section")
                }
            }
        }

        // 实时页是默认首页（section 0），切到导航页再切回来验证会话释放/重建
        compose.onNodeWithTag("realtime-page").assertIsDisplayed()
        compose.onNodeWithTag("home-section-1").performClick()
        compose.onNodeWithTag("realtime-page").assertDoesNotExist()
        compose.onNodeWithTag("home-section-0").performClick()
        compose.onNodeWithTag("realtime-page").assertIsDisplayed()
        compose.onNodeWithText("请先连接 comma 设备").assertIsDisplayed()
    }

    @Test
    fun localWebRtcRoadTrackNegotiatesRendersAndReportsResolution() {
        fixture = WebRtcFixture().also { it.startFixture() }
        val pageHeight = mutableStateOf(760.dp)
        var connectionClicks = 0
        val fusion = mutableStateOf(com.jixiexiaoge.drivingassist.data.LaneFusionEstimate(
            com.jixiexiaoge.drivingassist.data.LaneFusionState.RoadTypeBlocked))
        compose.setContent {
            // 分辨率/FPS 现在画在驾驶卡右下角角标上，所以要给 snapshot 才会渲染卡片
            NavipilotTheme {
                RealTimePage(
                    "127.0.0.1", snapshot = buildDashboardSnapshot(null, 1000),
                    amap = quietAmap(),
                    laneFusion = fusion.value,
                    modifier = androidx.compose.ui.Modifier.height(pageHeight.value),
                    onConnectionClick = { connectionClicks++ },
                )
            }
        }

        // 7.9.53：状态行撤了，IP/连接/分辨率改由卡片右下角角标承担
        compose.onNodeWithTag("drive-view-camera").assertIsDisplayed()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("320×240", substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertGreenSurface()
        compose.onNodeWithText("当前道路未启用车道融合（仅高速/高架）").assertIsDisplayed()
        assertTrue(com.jixiexiaoge.drivingassist.vision.LaneInference.activeSession == null)
        compose.runOnIdle {
            fusion.value = com.jixiexiaoge.drivingassist.data.LaneFusionEstimate()
        }
        compose.waitUntil(5000) {
            com.jixiexiaoge.drivingassist.vision.LaneInference.activeSession != null
        }
        val firstSession = com.jixiexiaoge.drivingassist.vision.LaneInference.activeSession?.id
        compose.runOnIdle {
            fusion.value = com.jixiexiaoge.drivingassist.data.LaneFusionEstimate(
                com.jixiexiaoge.drivingassist.data.LaneFusionState.RoadTypeBlocked)
        }
        compose.waitUntil(5000) {
            com.jixiexiaoge.drivingassist.vision.LaneInference.activeSession == null
        }
        assertGreenSurface()
        assertTrue("道路门只停推理，不重连摄像头", fixture?.requests == 1)
        compose.runOnIdle { fusion.value = com.jixiexiaoge.drivingassist.data.LaneFusionEstimate() }
        compose.waitUntil(5000) {
            com.jixiexiaoge.drivingassist.vision.LaneInference.activeSession?.id?.let { it != firstSession } == true
        }
        compose.onNodeWithTag("drive-view-camera-ip", useUnmergedTree = true).performTouchInput { click() }
        compose.runOnIdle { org.junit.Assert.assertEquals(1, connectionClicks) }
        assertTrue(fixture?.requests == 1)
        compose.onNodeWithTag("drive-view-gear").performClick()
        compose.onNodeWithTag("map-fullscreen").assertIsDisplayed()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(15_000) {
            fixture?.requests == 2 &&
                compose.onAllNodesWithText("320×240", substring = true, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
        }
        assertGreenSurface()
        compose.runOnIdle { pageHeight.value = 340.dp }
        compose.onNodeWithTag("ar-video-surface").assertDoesNotExist()
        compose.onNodeWithTag("drive-view-card").assertIsDisplayed()
        compose.waitUntil(5000) {
            com.jixiexiaoge.drivingassist.vision.LaneInference.activeSession == null
        }
        compose.runOnIdle { pageHeight.value = 760.dp }
        compose.waitUntil(15000) {
            fixture?.requests == 3 &&
                compose.onAllNodesWithText("320×240", substring = true, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
        }
        assertGreenSurface()
    }

    private fun assertGreenSurface() {
        val renderer = AtomicReference<SurfaceViewRenderer>()
        compose.runOnIdle {
            fun find(view: View): SurfaceViewRenderer? {
                if (view is SurfaceViewRenderer) return view
                if (view is ViewGroup) {
                    repeat(view.childCount) { index ->
                        find(view.getChildAt(index))?.let { return it }
                    }
                }
                return null
            }
            renderer.set(checkNotNull(find(compose.activity.window.decorView)))
        }
        val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        val done = CountDownLatch(1)
        var result = -1
        PixelCopy.request(
            renderer.get(),
            bitmap,
            { value -> result = value; done.countDown() },
            Handler(Looper.getMainLooper()),
        )
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertTrue(result == PixelCopy.SUCCESS)
        val pixel = bitmap.getPixel(160, 120)
        assertTrue(Color.green(pixel) > 120)
        assertTrue(Color.green(pixel) > Color.red(pixel) * 1.5)
        assertTrue(Color.green(pixel) > Color.blue(pixel) * 1.5)
        bitmap.recycle()
    }

    private class WebRtcFixture : NanoHTTPD("127.0.0.1", 5001), AutoCloseable {
        private val egl = EglBase.create()
        private val executor = Executors.newSingleThreadScheduledExecutor()
        private var factory: PeerConnectionFactory? = null
        private var peer: PeerConnection? = null
        private var source: org.webrtc.VideoSource? = null
        private var track: org.webrtc.VideoTrack? = null
        private var frameTask: java.util.concurrent.ScheduledFuture<*>? = null
        private var closed = false
        @Volatile var requests = 0

        fun startFixture() {
            start(SOCKET_READ_TIMEOUT, false)
        }

        override fun serve(session: IHTTPSession): Response {
            if (session.method != Method.POST || session.uri != "/stream") {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not found")
            }
            requests++
            Log.i("WebRtcFixture", "Received /stream request")
            val files = mutableMapOf<String, String>()
            session.parseBody(files)
            val offer = JSONObject(files["postData"].orEmpty())
            val answer = answer(offer.getString("sdp"))
            Log.i("WebRtcFixture", "Returning SDP answer")
            return newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                JSONObject().put("sdp", answer.description).put("type", "answer").toString(),
            )
        }

        @Synchronized
        private fun answer(offerSdp: String): SessionDescription {
            check(!closed) { "fixture is closed" }
            stopFrames()
            peer?.close()
            peer?.dispose()
            track?.dispose()
            source?.dispose()
            val createdFactory = factory ?: PeerConnectionFactory.builder()
                .setVideoEncoderFactory(
                    DefaultVideoEncoderFactory(egl.eglBaseContext, true, true),
                )
                .createPeerConnectionFactory()
                .also { factory = it }
            val connection = createdFactory.createPeerConnection(
                PeerConnection.RTCConfiguration(emptyList()).apply {
                    sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                },
                observer(),
            ) ?: error("fixture peer creation failed")
            peer = connection
            connection.setRemoteDescriptionBlocking(
                SessionDescription(SessionDescription.Type.OFFER, offerSdp),
            )
            val videoSource = createdFactory.createVideoSource(false)
            source = videoSource
            val videoTrack = createdFactory.createVideoTrack("road", videoSource)
            track = videoTrack
            connection.addTrack(videoTrack, listOf("road"))
            val answer = connection.createAnswerBlocking()
            connection.setLocalDescriptionBlocking(answer)
            startFrames(videoSource)
            return answer
        }

        private fun startFrames(videoSource: org.webrtc.VideoSource) {
            frameTask = executor.scheduleAtFixedRate({
                val buffer = JavaI420Buffer.allocate(320, 240)
                fill(buffer.dataY, 150)
                fill(buffer.dataU, 44)
                fill(buffer.dataV, 21)
                val frame = VideoFrame(buffer, 0, System.nanoTime())
                try {
                    videoSource.capturerObserver.onFrameCaptured(frame)
                } finally {
                    frame.release()
                }
            }, 0, 50, TimeUnit.MILLISECONDS)
        }

        private fun stopFrames() {
            frameTask?.cancel(false)
            frameTask = null
            // Drain any in-flight native callback before disposing its VideoSource.
            executor.submit {}.get(5, TimeUnit.SECONDS)
        }

        private fun fill(buffer: ByteBuffer, value: Int) {
            buffer.position(0)
            while (buffer.hasRemaining()) buffer.put(value.toByte())
            buffer.position(0)
        }

        private fun observer() = object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) = Unit
            override fun onStandardizedIceConnectionChange(state: PeerConnection.IceConnectionState?) = Unit
            override fun onConnectionChange(state: PeerConnection.PeerConnectionState?) = Unit
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit
            override fun onIceCandidate(candidate: IceCandidate?) = Unit
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
            override fun onAddStream(stream: MediaStream?) = Unit
            override fun onRemoveStream(stream: MediaStream?) = Unit
            override fun onDataChannel(channel: DataChannel?) = Unit
            override fun onRenegotiationNeeded() = Unit
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) = Unit
            override fun onTrack(transceiver: RtpTransceiver?) = Unit
        }

        @Synchronized
        override fun close() {
            closed = true
            stop()
            stopFrames()
            executor.shutdown()
            check(executor.awaitTermination(5, TimeUnit.SECONDS)) { "fixture frame worker did not stop" }
            track?.dispose()
            source?.dispose()
            peer?.close()
            peer?.dispose()
            factory?.dispose()
            egl.release()
        }
    }
}

private fun PeerConnection.createAnswerBlocking(): SessionDescription {
    val result = AtomicReference<SessionDescription>()
    val error = AtomicReference<String>()
    val done = CountDownLatch(1)
    createAnswer(object : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription?) {
            result.set(description)
            done.countDown()
        }
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(message: String?) {
            error.set(message)
            done.countDown()
        }
        override fun onSetFailure(message: String?) = Unit
    }, MediaConstraints())
    check(done.await(5, TimeUnit.SECONDS)) { "fixture answer timed out" }
    return result.get() ?: error(error.get() ?: "fixture answer failed")
}

private fun PeerConnection.setRemoteDescriptionBlocking(description: SessionDescription) {
    setDescriptionBlocking { observer -> setRemoteDescription(observer, description) }
}

private fun PeerConnection.setLocalDescriptionBlocking(description: SessionDescription) {
    setDescriptionBlocking { observer -> setLocalDescription(observer, description) }
}

private fun setDescriptionBlocking(action: (SdpObserver) -> Unit) {
    val error = AtomicReference<String>()
    val done = CountDownLatch(1)
    action(object : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription?) = Unit
        override fun onSetSuccess() {
            done.countDown()
        }
        override fun onCreateFailure(message: String?) = Unit
        override fun onSetFailure(message: String?) {
            error.set(message)
            done.countDown()
        }
    })
    check(done.await(5, TimeUnit.SECONDS)) { "fixture SDP timed out" }
    check(error.get() == null) { error.get() }
}
