package com.jixiexiaoge.drivingassist.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Bounded display telemetry from :7000/ws/compact_state, with no guessed Cap'n Proto offsets.
 * compactState is the truthful API; vehicleData remains a legacy HUD adapter, not automation
 * input. Unsupported legacy numerics are NaN; unsupported active/engageable stay false.
 * The lifecycle owner must call close(), or supply a scope whose Job it cancels.
 */
class CarrotWsClient(scope: CoroutineScope? = null) {
    companion object {
        private const val TAG = "CarrotWsClient"
        private const val RECONNECT_BASE_MS = 2000L
        private const val RECONNECT_MAX_MS = 30000L
        private const val DATA_TIMEOUT_MS = 4000L
        val DEFAULT_SERVICES = CompactStateDecoder.supportedServices
    }

    private val ownerJob = SupervisorJob(scope?.coroutineContext?.get(Job))
    private val workScope = CoroutineScope(
        (scope?.coroutineContext ?: Dispatchers.Default) + Dispatchers.Default + ownerJob)
    private val lock = Any()
    private val client = OkHttpClient.Builder().retryOnConnectionFailure(true)
        .pingInterval(15, TimeUnit.SECONDS).build()
    private val store = CompactTelemetryStore()
    private var socket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var timeoutJob: Job? = null
    private var generation = 0L
    private var stopped = true
    private var disposed = false
    private var negotiated = false
    private var host: String? = null
    private var reconnectAttempts = 0
    private var lastDataAtMs = 0L
    private var connectedAtMs = 0L

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState = _connectionState.asStateFlow()
    private val _isDataTimeout = MutableStateFlow(false)
    val isDataTimeout = _isDataTimeout.asStateFlow()
    private val _vehicleData = MutableStateFlow<VehicleData?>(null)
    val vehicleData = _vehicleData.asStateFlow()
    private val _compactState = MutableStateFlow(CompactTelemetry())
    val compactState = _compactState.asStateFlow()
    private val _decodeFailure = MutableStateFlow<CompactDecodeFailure?>(null)
    val decodeFailure = _decodeFailure.asStateFlow()
    private val _connectionError = MutableStateFlow<String?>(null)
    val connectionError = _connectionError.asStateFlow()

    init {
        ownerJob.invokeOnCompletion {
            synchronized(lock) {
                disposed = true
                stopLocked()
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        }
    }

    fun connect(host: String) = synchronized(lock) {
        check(!disposed && ownerJob.isActive) { "CarrotWsClient is closed" }
        val cleaned = host.trim().removePrefix("http://").removePrefix("https://")
            .removePrefix("ws://").removePrefix("wss://").substringBefore('/')
        require(cleaned.isNotEmpty()) { "empty device host" }
        // Caller supplies host/IP, optionally including :7000. URL builder handles IPv6.
        val url = okhttp3.HttpUrl.Builder().scheme("http")
            .host(cleaned.removeSuffix(":7000").removeSurrounding("[", "]")).port(7000)
            .addPathSegments("ws/compact_state")
            .addQueryParameter("services", DEFAULT_SERVICES.joinToString(",")).build()
            .toString().replaceFirst("http://", "ws://")
        if (!stopped && this.host == url) return@synchronized
        stopLocked()
        this.host = url
        stopped = false
        reconnectAttempts = 0
        _connectionError.value = null
        openLocked(url)
        timeoutJob = workScope.launch {
            while (isActive) {
                delay(250)
                synchronized(lock) {
                    if (!stopped) {
                        val now = monotonicMs()
                        _isDataTimeout.value = now - maxOf(lastDataAtMs, connectedAtMs) > DATA_TIMEOUT_MS
                        publish(store.snapshot(now))
                    }
                }
            }
        }
    }

    /** Stops this connection and cancels its reconnects; an explicit connect may start a new one. */
    fun disconnect() = synchronized(lock) { stopLocked() }

    /** Permanently cancels the owned child scope and releases this client's resources. */
    fun close() {
        synchronized(lock) { disposed = true; stopLocked() }
        workScope.cancel()
    }

    fun resetReconnectAttempts() = synchronized(lock) { reconnectAttempts = 0 }

    private fun stopLocked() {
        stopped = true
        generation++
        reconnectJob?.cancel()
        reconnectJob = null
        timeoutJob?.cancel()
        timeoutJob = null
        socket?.cancel()
        socket = null
        negotiated = false
        store.clear()
        publish(store.snapshot(monotonicMs()))
        _connectionState.value = ConnectionState.DISCONNECTED
        _isDataTimeout.value = false
        lastDataAtMs = 0
        connectedAtMs = 0
    }

    private fun openLocked(url: String) {
        val token = ++generation
        negotiated = false
        _connectionState.value = ConnectionState.CONNECTING
        connectedAtMs = monotonicMs()
        socket = client.newWebSocket(Request.Builder().url(url).build(), Listener(token))
    }

    private fun accepts(token: Long) = !stopped && !disposed && ownerJob.isActive && token == generation

    private inner class Listener(private val token: Long) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(lock) { if (!accepts(token)) webSocket.cancel() }
        }

        override fun onMessage(webSocket: WebSocket, text: String) = synchronized(lock) {
            if (!accepts(token)) return@synchronized
            try {
                if (text.length > 16 * 1024) throw CompactDecodeException("compact hello too large")
                val hello = JSONObject(text)
                if (hello.optString("mode") != "carrot-state-v1") {
                    throw CompactDecodeException("unsupported compact hello")
                }
                val services = hello.optJSONArray("services") ?: throw CompactDecodeException("missing hello services")
                val offered = (0 until services.length()).map { services.getString(it) }.toSet()
                if (!offered.containsAll(DEFAULT_SERVICES)) throw CompactDecodeException("hello missing subscriptions")
                negotiated = true
                reconnectAttempts = 0
                _connectionError.value = null
                _connectionState.value = ConnectionState.CONNECTED
            } catch (error: org.json.JSONException) {
                recordFailure("malformed hello: ${error.message}")
                webSocket.cancel()
            } catch (error: CompactDecodeException) {
                recordFailure(error.message ?: "invalid hello")
                webSocket.cancel()
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = synchronized(lock) {
            if (!accepts(token)) return@synchronized
            try {
                if (!negotiated) throw CompactDecodeException("binary frame before compact hello")
                if (bytes.size > CompactStateDecoder.MAX_MESSAGE_BYTES) throw CompactDecodeException("message exceeds 1 MiB")
                val frames = CompactStateDecoder.decode(bytes.toByteArray())
                if (frames.isNotEmpty()) {
                    val now = monotonicMs()
                    publish(store.accept(frames, now))
                    if (store.modelResetLastBatch) {
                        webSocket.cancel()
                        lost(token, "model frame order reset; renegotiating telemetry")
                        return@synchronized
                    }
                    if (store.rejectedFramesLastBatch > 0) {
                        recordFailure("rejected ${store.rejectedFramesLastBatch} duplicate or out-of-order samples")
                    }
                    if (store.acceptedFramesLastBatch > 0) {
                        lastDataAtMs = now
                        _isDataTimeout.value = false
                    }
                }
            } catch (error: CompactDecodeException) {
                recordFailure(error.message ?: "invalid frame")
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(token, "closed $code: $reason")
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = lost(token, t.message ?: "socket failure")
    }

    private fun lost(token: Long, reason: String) = synchronized(lock) {
        if (!accepts(token)) return@synchronized
        // Invalidate all subsequent callbacks from this attempt before scheduling the next.
        generation++
        socket = null
        negotiated = false
        store.clear()
        publish(store.snapshot(monotonicMs()))
        _connectionState.value = ConnectionState.DISCONNECTED
        _connectionError.value = reason
        Log.w(TAG, "Compact telemetry disconnected: $reason")
        reconnectJob?.cancel()
        val retryToken = generation
        val waitMs = min(RECONNECT_BASE_MS * (1L shl min(reconnectAttempts++, 4)), RECONNECT_MAX_MS)
        reconnectJob = workScope.launch {
            delay(waitMs)
            synchronized(lock) {
                if (accepts(retryToken)) host?.let { openLocked(it) }
            }
        }
    }

    private fun recordFailure(message: String) {
        _decodeFailure.value = CompactDecodeFailure(message, monotonicMs(), (_decodeFailure.value?.count ?: 0) + 1)
        Log.w(TAG, "Rejected compact telemetry: $message")
    }

    private fun publish(snapshot: CompactTelemetry) {
        _compactState.value = snapshot
        _vehicleData.value = snapshot.toVehicleData(System.currentTimeMillis())
    }

    private fun monotonicMs() = System.nanoTime() / 1_000_000
}

data class CompactDecodeFailure(val message: String, val receivedAtMs: Long, val count: Long)

data class VehicleData(
    val carState: CarState? = null,
    val modelV2: ModelV2? = null,
    val controlsState: ControlsState? = null,
    val selfdriveState: SelfdriveState? = null,
    val carrotMan: Map<String, Any?>? = null,
    val timestamp: Long = System.currentTimeMillis(),
)

data class CarState(
    val vEgo: Float = Float.NaN,
    val steeringAngleDeg: Float = Float.NaN,
    val leftLatDist: Float = Float.NaN,
    val leftBlindspot: Boolean = false,
    val rightBlindspot: Boolean = false,
    val leftBlinker: Boolean = false,
    val rightBlinker: Boolean = false,
)

data class ModelV2(
    val leadX: Float = Float.NaN,
    val leadV: Float = Float.NaN,
    val leadProb: Float = Float.NaN,
    val laneLineProbs: List<Float> = emptyList(),
    val leftDist: Float = Float.NaN,
    val rightDist: Float = Float.NaN,
    val orientationRate: Float = Float.NaN,
)

data class ControlsState(
    val enabled: Boolean = false,
    val active: Boolean = false,
    val vCruise: Float = Float.NaN,
    val angleSteers: Float = Float.NaN,
    val curvature: Float = Float.NaN,
)

data class SelfdriveState(
    val enabled: Boolean = false,
    val active: Boolean = false,
    val engageAllowed: Boolean = false,
)

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }
