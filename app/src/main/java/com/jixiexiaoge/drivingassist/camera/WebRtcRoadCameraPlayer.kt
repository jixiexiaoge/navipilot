@file:Suppress("MagicNumber", "TooManyFunctions")

package com.jixiexiaoge.drivingassist.camera

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONException
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "WebRtcRoadCamera"

internal enum class CameraPhase {
    CONNECTING,
    WAITING,
    LIVE,
    STALE,
    BUSY,

    /**
     * 5001 信令端口连不上：设备虽然在（IP 已发现），但车机侧没开视频流服务。
     * 与一般 [RETRYING] 区分开，UI 才能给出「需开启视频流功能」这种可操作的提示。
     */
    UNREACHABLE,
    RETRYING,
    STOPPED,
}

internal data class CameraPlayback(
    val phase: CameraPhase = CameraPhase.STOPPED,
    val detail: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val fps: Int = 0,
)

internal data class WebRtcStreamAnswer(val sdp: String, val type: String)

internal object WebRtcSignaling {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /** 长焦道路摄像头：**唯一订阅的画面源**（视野与车道线密度和旧版一致） */
    const val CAMERA_ROAD = "road"

    /**
     * 广角摄像头。
     *
     * 7.9.37 曾把默认画面切到这里，实车反馈广角视野过宽：模型把相邻车道也当成当前车道，
     * 车道数偏高、车道宽度也偏，因此恢复为只订阅 [CAMERA_ROAD]。
     * 本常量仅保留给车端联调（手动指定 cameras 时使用），不再参与候选。
     */
    const val CAMERA_WIDE = "wideRoad"

    /**
     * 候选顺序：**只有长焦 road 一项**。
     *
     * 不再「广角优先、失败回退」，避免重新把画面切回广角；
     * 车端没有 road 轨时就直接停在失败/重连状态，而不是悄悄换成另一种视野。
     */
    val CAMERA_CANDIDATES: List<List<String>> = listOf(
        listOf(CAMERA_ROAD),
    )

    fun streamUrl(host: String) = "http://$host:5001/stream"

    /**
     * @param cameras 订阅的摄像头列表。
     *   这里**一次只发一个候选**，订阅源完全由客户端决定，
     *   不依赖车端 webrtcd 对多摄像头的选择策略。默认只订阅长焦 `road`。
     */
    fun requestBody(
        offerSdp: String,
        clientId: String,
        cameras: List<String> = listOf(CAMERA_ROAD),
    ): String = JSONObject()
        .put("sdp", offerSdp)
        .put("cameras", org.json.JSONArray(cameras))
        .put("bridge_services_in", org.json.JSONArray())
        .put("bridge_services_out", org.json.JSONArray())
        .put("client_id", clientId)
        .put("takeover", false)
        .put("carrot_state", false)
        .toString()

    fun request(url: String, body: String): Request = Request.Builder()
        .url(url)
        .post(body.toRequestBody(jsonMediaType))
        .build()

    fun parseAnswer(body: String): WebRtcStreamAnswer {
        val json = try {
            JSONObject(body)
        } catch (error: JSONException) {
            throw IOException("Invalid WebRTC answer JSON", error)
        }
        val sdp = json.optString("sdp").takeIf { it.isNotBlank() }
            ?: throw IOException("WebRTC answer is missing SDP")
        return WebRtcStreamAnswer(sdp, json.optString("type", "answer"))
    }

    fun error(code: Int, body: String): IOException {
        val json = runCatching { JSONObject(body) }.getOrNull()
        return if (code == 409 && json?.optString("code") == "carrot_vision_busy") {
            WebRtcBusyException(json.optString("error", "Road camera is active on another client"))
        } else {
            IOException("WebRTC signaling failed (HTTP $code): ${body.take(240)}")
        }
    }
}

internal class WebRtcBusyException(message: String) : IOException(message)

/**
 * 是否属于「5001 端口压根连不上」。
 *
 * 这类失败不是画面问题，而是车机侧没开视频流服务（或设备已掉线），重连再多次也没用，
 * 所以单独归到 [CameraPhase.UNREACHABLE]，让界面提示「需开启视频流功能」。
 * 信令回了 HTTP 错误码、或 ICE/轨道阶段失败，都算「端口能连上」，仍走普通重连。
 */
internal fun isSignalingUnreachable(error: Throwable): Boolean {
    if (error is java.net.ConnectException || error is java.net.SocketTimeoutException ||
        error is java.net.NoRouteToHostException || error is java.net.UnknownHostException ||
        error is java.net.PortUnreachableException
    ) {
        return true
    }
    val text = error.message.orEmpty()
    return UNREACHABLE_HINTS.any { text.contains(it, ignoreCase = true) }
}

private val UNREACHABLE_HINTS = listOf(
    "failed to connect",
    "connection refused",
    "connect timed out",
    "timed out",
    "timeout",
    "unable to resolve host",
)

/**
 * 视频帧旁路回调。
 *
 * 运行在 **WebRTC 解码线程**，只允许做「拷一份 Y 平面」这种量级的工作；
 * 任何耗时操作都会直接卡住解码 → 画面掉帧。
 */
internal fun interface RoadFrameTap {
    fun onRoadFrame(frame: VideoFrame)
}

internal class WebRtcRoadCameraPlayer(context: Context) : Closeable {
    private val appContext = context.applicationContext
    private val engine = WebRtcEngine.get(appContext)

    /** 叠加层的帧入口；置空即完全回到「纯预览」行为 */
    @Volatile
    var frameTap: RoadFrameTap? = null

    /**
     * 摄像头候选顺序：**只有长焦 road**（见 [WebRtcSignaling.CAMERA_CANDIDATES]）。
     * 广角视野过宽会让车道数/车道宽度的估计偏高，已不再参与候选。
     */
    private val cameraCandidates: List<List<String>> = WebRtcSignaling.CAMERA_CANDIDATES

    /** 当前正在尝试的候选下标；只有一个候选，正常不会变化 */
    private var cameraIndex = 0

    /**
     * 上一次失败是不是「5001 连不上」。
     *
     * 为 true 时重试循环不再把状态刷回 [CameraPhase.CONNECTING]，否则界面会在
     * 「正在协商 WebRTC…」和「需开启视频流功能」之间来回闪。画面一旦接上就复位。
     */
    private var signalingUnreachable = false

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .build()
    private val mutableState = MutableStateFlow(CameraPlayback())
    private val closed = AtomicBoolean()
    val state = mutableState.asStateFlow()
    val eglContext: EglBase.Context get() = engine.eglBase.eglBaseContext

    suspend fun play(host: String, renderer: SurfaceViewRenderer) = withContext(Dispatchers.Default) {
        Log.i(TAG, "Starting WebRTC road camera for $host (camera=${cameraCandidates[cameraIndex]})")
        var attempts = 0
        try {
            while (currentCoroutineContext().isActive && !closed.get()) {
                val startedAt = System.nanoTime()
                // 5001 连不上时保持 UNREACHABLE，不闪回「正在协商」
                if (!signalingUnreachable) mutableState.value = CameraPlayback(CameraPhase.CONNECTING)
                try {
                    stream(host, renderer, cameraCandidates[cameraIndex])
                } catch (error: CancellationException) {
                    throw error
                } catch (error: WebRtcBusyException) {
                    // 摄像头被别的客户端占用：保持 BUSY，不切换订阅源
                    mutableState.value = CameraPlayback(CameraPhase.BUSY, error.message.orEmpty())
                } catch (error: IOException) {
                    interrupted(error)
                    fallbackCamera()
                } catch (error: IllegalStateException) {
                    interrupted(error)
                    fallbackCamera()
                } catch (error: IllegalArgumentException) {
                    interrupted(error)
                    fallbackCamera()
                }
                if (System.nanoTime() - startedAt >= STABLE_SESSION_NS) attempts = 0
                delay((RETRY_BASE_MS shl attempts.coerceAtMost(5)).coerceAtMost(RETRY_MAX_MS))
                attempts++
            }
        } finally {
            mutableState.value = CameraPlayback()
        }
    }

    /**
     * 订阅源只有一个（长焦 road），这里保留候选推进的写法只为兼容将来的多源扩展。
     *
     * 只前进不后退；用户点「重连」会重建本对象，[cameraIndex] 归零。
     */
    private fun fallbackCamera() {
        if (cameraIndex < cameraCandidates.lastIndex) {
            cameraIndex++
            Log.i(TAG, "摄像头回退到 ${cameraCandidates[cameraIndex]}")
        }
    }

    private fun interrupted(error: Exception) {
        Log.w(TAG, "WebRTC road stream interrupted", error)
        signalingUnreachable = isSignalingUnreachable(error)
        mutableState.value = CameraPlayback(
            if (signalingUnreachable) CameraPhase.UNREACHABLE else CameraPhase.RETRYING,
            error.message ?: error.javaClass.simpleName,
        )
    }

    private suspend fun stream(host: String, renderer: SurfaceViewRenderer, cameras: List<String>) {
        val session = WebRtcSession(engine.factory, renderer, mutableState) { frameTap }
        try {
            session.start(client, WebRtcSignaling.streamUrl(host), clientId(), cameras)
            // 首帧已到：说明 5001 通、视频流正常，清掉「不可达」标记
            signalingUnreachable = false
            session.monitor()
        } finally {
            session.close()
            renderer.clearImage()
        }
    }

    private fun clientId(): String {
        val prefs = appContext.getSharedPreferences("camera_webrtc", Context.MODE_PRIVATE)
        val existing = prefs.getString("client_id", null)
        if (!existing.isNullOrBlank()) return existing
        val generated = "navipilot-${UUID.randomUUID()}"
        prefs.edit().putString("client_id", generated).apply()
        return generated
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    companion object {
        private const val RETRY_BASE_MS = 500L
        private const val RETRY_MAX_MS = 20_000L
        private const val STABLE_SESSION_NS = 10_000_000_000L

        fun prewarm(context: Context) {
            WebRtcEngine.get(context.applicationContext)
        }

    }
}

private class WebRtcEngine private constructor(
    val eglBase: EglBase,
    val factory: PeerConnectionFactory,
) {
    companion object {
        @Volatile
        private var instance: WebRtcEngine? = null

        fun get(context: Context): WebRtcEngine = instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }

        private fun create(context: Context): WebRtcEngine {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions(),
            )
            val eglBase = EglBase.create()
            val factory = PeerConnectionFactory.builder()
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
                .createPeerConnectionFactory()
            return WebRtcEngine(eglBase, factory)
        }
    }
}

private class WebRtcSession(
    private val factory: PeerConnectionFactory,
    private val renderer: SurfaceViewRenderer,
    private val state: MutableStateFlow<CameraPlayback>,
    frameTap: () -> RoadFrameTap?,
) : Closeable {
    private val trackReady = CompletableDeferred<Unit>()
    private val firstFrame = CompletableDeferred<Unit>()
    private val failure = CompletableDeferred<IOException>()
    private val frameSink = PlaybackVideoSink(renderer, state, firstFrame, frameTap)
    private var peer: PeerConnection? = null
    private var track: VideoTrack? = null
    private var dataChannel: DataChannel? = null

    @Suppress("ThrowsCount")
    suspend fun start(
        client: OkHttpClient,
        url: String,
        clientId: String,
        cameras: List<String> = listOf(WebRtcSignaling.CAMERA_ROAD),
    ) {
        val observer = peerObserver()
        val config = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val connection = factory.createPeerConnection(config, observer)
            ?: throw IOException("Unable to create WebRTC peer connection")
        peer = connection
        dataChannel = connection.createDataChannel(
            "data",
            DataChannel.Init().apply {
                ordered = false
                maxRetransmits = 0
            },
        )
        connection.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY),
        )
        val offer = connection.createOfferAwait()
        connection.setLocalDescriptionAwait(offer)
        awaitIceGathering(connection)
        val localSdp = connection.localDescription?.description
            ?: throw IOException("WebRTC local SDP is unavailable")
        Log.i(TAG, "Posting WebRTC offer to $url (${localSdp.length} bytes)")
        val response = withContext(Dispatchers.IO) {
            client.newCall(
                WebRtcSignaling.request(url, WebRtcSignaling.requestBody(localSdp, clientId, cameras)),
            ).execute()
        }
        val responseBody = response.use { it.body?.string().orEmpty() }
        Log.i(TAG, "WebRTC signaling response HTTP ${response.code} (${responseBody.length} bytes)")
        if (!response.isSuccessful) throw WebRtcSignaling.error(response.code, responseBody)
        val answer = WebRtcSignaling.parseAnswer(responseBody)
        connection.setRemoteDescriptionAwait(
            SessionDescription(SessionDescription.Type.fromCanonicalForm(answer.type), answer.sdp),
        )
        Log.i(TAG, "WebRTC answer applied; waiting for road track")
        withTimeout(TRACK_TIMEOUT_MS) { trackReady.await() }
        state.value = CameraPlayback(CameraPhase.WAITING)
        withTimeout(FIRST_FRAME_TIMEOUT_MS) { firstFrame.await() }
        Log.i(TAG, "First WebRTC road frame received")
    }

    suspend fun monitor() {
        while (currentCoroutineContext().isActive) {
            if (failure.isCompleted) throw failure.await()
            val now = System.nanoTime()
            val lastFrame = frameSink.lastFrameNs.get()
            val age = if (lastFrame == 0L) Long.MAX_VALUE else now - lastFrame
            if (age > FRAME_RECONNECT_NS) throw IOException("No WebRTC video frame for 20 seconds")
            frameSink.publish(now, age > FRAME_STALE_NS)
            delay(250)
        }
    }

    private fun peerObserver() = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
            if (newState == PeerConnection.IceConnectionState.FAILED ||
                newState == PeerConnection.IceConnectionState.CLOSED
            ) fail("WebRTC ICE $newState")
        }
        override fun onStandardizedIceConnectionChange(newState: PeerConnection.IceConnectionState?) =
            onIceConnectionChange(newState)
        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
            if (newState == PeerConnection.PeerConnectionState.FAILED ||
                newState == PeerConnection.PeerConnectionState.CLOSED
            ) fail("WebRTC connection $newState")
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) = Unit
        override fun onIceCandidate(candidate: IceCandidate?) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
        override fun onAddStream(stream: MediaStream?) = Unit
        override fun onRemoveStream(stream: MediaStream?) = Unit
        override fun onDataChannel(channel: DataChannel?) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
            attach(receiver?.track())
        }
        override fun onTrack(transceiver: RtpTransceiver?) {
            attach(transceiver?.receiver?.track())
        }
    }

    private fun attach(mediaTrack: MediaStreamTrack?) {
        val videoTrack = mediaTrack as? VideoTrack ?: return
        if (track != null) return
        track = videoTrack
        Log.i(TAG, "Attached remote WebRTC video track ${videoTrack.id()}")
        videoTrack.setEnabled(true)
        videoTrack.addSink(frameSink)
        trackReady.complete(Unit)
    }

    private fun fail(message: String) {
        failure.complete(IOException(message))
    }

    override fun close() {
        track?.removeSink(frameSink)
        track = null
        dataChannel?.close()
        dataChannel?.dispose()
        dataChannel = null
        peer?.close()
        peer?.dispose()
        peer = null
    }

    companion object {
        private const val TRACK_TIMEOUT_MS = 3_000L
        private const val FIRST_FRAME_TIMEOUT_MS = 6_000L
        private const val ICE_GATHER_TIMEOUT_MS = 1_200L
        private const val FRAME_STALE_NS = 2_000_000_000L
        private const val FRAME_RECONNECT_NS = 20_000_000_000L

        private suspend fun awaitIceGathering(peer: PeerConnection) {
            val start = System.nanoTime()
            while (peer.iceGatheringState() != PeerConnection.IceGatheringState.COMPLETE &&
                System.nanoTime() - start < ICE_GATHER_TIMEOUT_MS * 1_000_000
            ) delay(20)
        }
    }
}

private class PlaybackVideoSink(
    private val renderer: SurfaceViewRenderer,
    private val state: MutableStateFlow<CameraPlayback>,
    private val firstFrame: CompletableDeferred<Unit>,
    private val frameTap: () -> RoadFrameTap?,
) : VideoSink {
    val lastFrameNs = AtomicLong()
    private val frames = AtomicLong()
    private val width = AtomicInteger()
    private val height = AtomicInteger()
    private var sampledAtNs = System.nanoTime()
    private var sampledFrames = 0L
    private var fps = 0
    private var tapBroken = false

    override fun onFrame(frame: VideoFrame) {
        renderer.onFrame(frame)
        width.set(if (frame.rotation % 180 == 0) frame.buffer.width else frame.buffer.height)
        height.set(if (frame.rotation % 180 == 0) frame.buffer.height else frame.buffer.width)
        frames.incrementAndGet()
        lastFrameNs.set(System.nanoTime())
        firstFrame.complete(Unit)
        // 旁路：叠加层挂上才会走到这里；没挂就是零开销
        if (!tapBroken) {
            val tap = frameTap()
            if (tap != null) {
                try {
                    tap.onRoadFrame(frame)
                } catch (error: Throwable) {
                    tapBroken = true
                    Log.w(TAG, "frame tap disabled after failure", error)
                }
            }
        }
    }

    fun publish(nowNs: Long, stale: Boolean) {
        if (nowNs - sampledAtNs >= 1_000_000_000L) {
            val current = frames.get()
            fps = ((current - sampledFrames) * 1_000_000_000L / (nowNs - sampledAtNs)).toInt()
            sampledFrames = current
            sampledAtNs = nowNs
        }
        state.value = CameraPlayback(
            phase = if (stale) CameraPhase.STALE else CameraPhase.LIVE,
            width = width.get(),
            height = height.get(),
            fps = if (stale) 0 else fps,
        )
    }
}

private suspend fun PeerConnection.createOfferAwait(): SessionDescription =
    suspendCancellableSdp { observer -> createOffer(observer, MediaConstraints()) }

private suspend fun PeerConnection.setLocalDescriptionAwait(description: SessionDescription) {
    suspendCancellableSdp { observer -> setLocalDescription(observer, description) }
}

private suspend fun PeerConnection.setRemoteDescriptionAwait(description: SessionDescription) {
    suspendCancellableSdp { observer -> setRemoteDescription(observer, description) }
}

private suspend fun suspendCancellableSdp(
    action: (SdpObserver) -> Unit,
): SessionDescription = kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
    action(object : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription?) {
            if (description == null) continuation.resumeWith(Result.failure(IOException("Empty SDP")))
            else continuation.resumeWith(Result.success(description))
        }
        override fun onSetSuccess() {
            continuation.resumeWith(Result.success(SessionDescription(SessionDescription.Type.OFFER, "")))
        }
        override fun onCreateFailure(message: String?) {
            continuation.resumeWith(Result.failure(IOException(message ?: "SDP creation failed")))
        }
        override fun onSetFailure(message: String?) {
            continuation.resumeWith(Result.failure(IOException(message ?: "SDP application failed")))
        }
    })
}
