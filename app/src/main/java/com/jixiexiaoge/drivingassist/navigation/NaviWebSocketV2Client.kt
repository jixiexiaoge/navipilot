package com.jixiexiaoge.drivingassist.navigation

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Owns Carrot v2 negotiation and JSON sockets. Image/render catalog entries are
 * required by strict28 receivers, but are not implemented by this publisher.
 * All socket callbacks are scoped to their connection attempt and session.
 */
class NaviWebSocketV2Client(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    private val client: OkHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .pingInterval(NaviV2Constants.CONTROL_HEARTBEAT_S, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        private const val TAG = "NaviV2Client"
        private const val APP_VERSION = "amap_auto_1.0"
        private val DELIVERY_MODES = setOf("on_change", "periodic", "on_change_with_heartbeat")

        internal fun reconnectDelayMs(failures: Int): Long {
            val exponent = (failures.coerceAtLeast(1) - 1).coerceAtMost(4)
            return (NaviV2Constants.CONTROL_RECONNECT_DELAY_MS * (1L shl exponent))
                .coerceAtMost(NaviV2Constants.MAX_RECONNECT_DELAY_MS)
        }

        internal fun validateManifest(json: JSONObject): NaviV2Session {
            require(json.getString("type") == "subscription_manifest") { "invalid manifest type" }
            require(positiveInt(json, "protocol_version") == NaviV2Constants.PROTOCOL_VERSION) {
                "unsupported manifest protocol_version"
            }
            val sessionValue = json.get("session_id")
            require(sessionValue is String) { "session_id must be a string" }
            val sessionId = sessionValue
            require(sessionId.isNotBlank() && sessionId.matches(Regex("[A-Za-z0-9_-]+"))) {
                "invalid manifest session_id"
            }
            val revision = positiveInt(json, "revision")
            val entries = json.getJSONArray("streams")
            val catalog = NaviV2Constants.CATALOG.toSet()
            require(entries.length() == catalog.size) { "manifest must contain the complete catalog" }
            val streams = linkedMapOf<String, StreamConfig>()
            val handles = mutableSetOf<Int>()
            for (i in 0 until entries.length()) {
                val entry = entries.getJSONObject(i)
                val kind = entry.getString("kind")
                val name = entry.getString("name")
                val key = "$kind:$name"
                require(kind to name in catalog && key !in streams) {
                    "unknown or duplicate manifest stream: $key"
                }
                val schemaVersion = positiveInt(entry, "schema_version")
                require(schemaVersion == 1) { "unsupported schema_version for $key" }
                val handle = positiveInt(entry, "stream_handle")
                require(handle <= 65535 && handles.add(handle)) {
                    "invalid or duplicate stream_handle"
                }
                require(entry.get("enabled") is Boolean) { "enabled must be boolean for $key" }
                val requestedEnabled = entry.getBoolean("enabled")
                val paramsObject = entry.getJSONObject("params")
                val params = paramsObject.keys().asSequence()
                    .associateWith { paramsObject.get(it) }
                if (kind == "json") {
                    require(paramsObject.getString("delivery_mode") in DELIVERY_MODES) {
                        "unsupported delivery_mode for $key"
                    }
                    require(positiveInt(paramsObject, "interval_ms") in 200..5000) {
                        "unsupported interval_ms for $key"
                    }
                    positiveInt(paramsObject, "stale_timeout_ms")
                }
                streams[key] = StreamConfig(
                    kind, name, schemaVersion, handle,
                    enabled = requestedEnabled && kind == "json",
                    params = params
                )
            }
            require(streams.keys == catalog.map { (kind, name) -> "$kind:$name" }.toSet()) {
                "manifest catalog mismatch"
            }
            return NaviV2Session(sessionId, revision, streams)
        }

        private fun positiveInt(json: JSONObject, key: String): Int {
            val value = json.get(key)
            require(value is Number && value.toDouble().isFinite() &&
                value.toDouble() == value.toInt().toDouble() && value.toInt() > 0) {
                "$key must be a positive integer"
            }
            return value.toInt()
        }
    }

    private class SocketSlot {
        var socket: WebSocket? = null
        var open = false
    }

    private val lock = Any()
    private val _connectionState = MutableStateFlow(NaviV2ConnectionState.DISCONNECTED)
    val connectionState = _connectionState.asStateFlow()
    private var targetHost = ""
    private var targetPort = NaviV2Constants.DEFAULT_PORT
    private var stopped = true
    private var generation = 0L
    private var control: SocketSlot? = null
    private var currentSession: NaviV2Session? = null
    private val jsonStreams = mutableMapOf<String, SocketSlot>()
    private val streamSequences = mutableMapOf<String, Long>()
    private val sendFailures = mutableSetOf<String>()
    private var controlReconnectJob: Job? = null
    private var connectionTimeoutJob: Job? = null
    private var controlFailCount = 0
    private var discoveryReceived = false
    private var readyNotified = false
    private var readySinceMs = 0L

    /** Fired when control and EVERY enabled JSON stream are open, including after recovery. */
    var onSessionReady: ((NaviV2Session) -> Unit)? = null
    var onStateChanged: ((NaviV2ConnectionState) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    fun setTarget(host: String, port: Int = NaviV2Constants.DEFAULT_PORT) = synchronized(lock) {
        require(port in 1..65535) { "invalid target port" }
        val changed = targetHost != host || targetPort != port
        targetHost = host
        targetPort = port
        discoveryReceived = host.isNotBlank()
        if (!stopped && changed) {
            invalidateConnections()
            controlFailCount = 0
            connectControl()
        } else if (!stopped && control == null && controlReconnectJob == null) {
            connectControl()
        }
    }

    fun start() = synchronized(lock) {
        stopped = false
        if (control == null && controlReconnectJob == null) connectControl()
    }

    fun stop() = synchronized(lock) {
        stopped = true
        invalidateConnections()
        discoveryReceived = false
        controlFailCount = 0
        setState(NaviV2ConnectionState.DISCONNECTED)
    }

    fun nextSequence(kind: String, name: String): Long = synchronized(lock) {
        val key = "$kind:$name"
        ((streamSequences[key] ?: 0L) + 1L).also { streamSequences[key] = it }
    }

    /** Effective session settings: unsupported image/render streams are disabled. */
    fun getSession(): NaviV2Session? = synchronized(lock) { currentSession }

    fun getStreamConfig(kind: String, name: String): StreamConfig? = synchronized(lock) {
        currentSession?.streams?.get("$kind:$name")
    }

    fun isStreamReady(name: String): Boolean = synchronized(lock) {
        !stopped && control?.open == true &&
            currentSession?.streams?.get("json:$name")?.enabled == true &&
            jsonStreams["json:$name"]?.open == true
    }

    fun isReady(): Boolean = synchronized(lock) { isReadyLocked() }

    private fun isReadyLocked(): Boolean {
        val session = currentSession ?: return false
        return !stopped && control?.open == true &&
            session.streams.values.filter { it.kind == "json" && it.enabled }
                .all { jsonStreams["json:${it.name}"]?.open == true }
    }

    private fun setState(state: NaviV2ConnectionState) {
        if (_connectionState.value != state) {
            _connectionState.value = state
            onStateChanged?.invoke(state)
        }
    }

    private fun reportError(message: String, error: Throwable? = null) {
        Log.e(TAG, message, error)
        onError?.invoke(message)
    }

    private fun isCurrent(attempt: Long, slot: SocketSlot): Boolean =
        !stopped && generation == attempt && control === slot

    private fun connectControl() {
        if (stopped || control != null) return
        if (targetHost.isBlank()) {
            setState(NaviV2ConnectionState.DISCOVERING)
            return
        }
        val attempt = ++generation
        val slot = SocketSlot()
        control = slot // Reserve synchronously, before newWebSocket returns.
        setState(NaviV2ConnectionState.CONTROL_CONNECTING)
        if (!isCurrent(attempt, slot)) return
        armOpeningTimeout(attempt, slot)
        try {
            val request = Request.Builder()
                .url("ws://$targetHost:$targetPort/api/navi/ws/v2/control/$APP_VERSION")
                .build()
            val socket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) = synchronized(lock) {
                    if (!isCurrent(attempt, slot)) {
                        ws.cancel()
                        return@synchronized
                    }
                    slot.socket = ws
                    slot.open = true
                    setState(NaviV2ConnectionState.NEGOTIATING)
                    if (isCurrent(attempt, slot) && !ws.send(buildRequirementsQuery().toString())) {
                        reportError("requirements_query enqueue failed")
                        if (isCurrent(attempt, slot)) disconnectAndRetry()
                    }
                }

                override fun onMessage(ws: WebSocket, text: String) = synchronized(lock) {
                    if (isCurrent(attempt, slot) && slot.open) handleControlMessage(text, attempt, slot)
                }

                override fun onClosing(ws: WebSocket, code: Int, reason: String) = synchronized(lock) {
                    if (isCurrent(attempt, slot)) disconnectAndRetry()
                    ws.close(code, reason)
                    Unit
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) = synchronized(lock) {
                    if (isCurrent(attempt, slot)) disconnectAndRetry()
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) = synchronized(lock) {
                    if (isCurrent(attempt, slot)) {
                        reportError("control WebSocket failed: ${t.message}", t)
                        if (isCurrent(attempt, slot)) disconnectAndRetry()
                    }
                }
            })
            if (isCurrent(attempt, slot)) slot.socket = socket else socket.cancel()
        } catch (error: Exception) {
            if (isCurrent(attempt, slot)) {
                reportError("control connection failed: ${error.message}", error)
                if (isCurrent(attempt, slot)) disconnectAndRetry()
            }
        }
    }

    private fun armOpeningTimeout(attempt: Long, slot: SocketSlot) {
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = scope.launch {
            delay(NaviV2Constants.MAX_RECONNECT_DELAY_MS)
            synchronized(lock) {
                if (isCurrent(attempt, slot) && !isReadyLocked()) {
                    reportError("navigation session opening timed out")
                    disconnectAndRetry()
                }
            }
        }
    }

    private fun invalidateConnections() {
        ++generation
        controlReconnectJob?.cancel()
        controlReconnectJob = null
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = null
        val sockets = jsonStreams.values.mapNotNull { it.socket } + listOfNotNull(control?.socket)
        control = null
        currentSession = null
        jsonStreams.clear()
        streamSequences.clear()
        sendFailures.clear()
        readyNotified = false
        readySinceMs = 0
        sockets.forEach { it.cancel() }
    }

    private fun disconnectAndRetry() {
        if (stopped || control == null) return
        if (readySinceMs != 0L &&
            System.currentTimeMillis() - readySinceMs >= NaviV2Constants.MAX_RECONNECT_DELAY_MS) {
            controlFailCount = 0
        }
        controlFailCount = (controlFailCount + 1).coerceAtMost(30)
        invalidateConnections()
        if (controlFailCount >= NaviV2Constants.CONTROL_FAIL_RESET_THRESHOLD) {
            discoveryReceived = false
        }
        setState(NaviV2ConnectionState.RECONNECTING)
        if (stopped || control != null) return
        val expectedGeneration = generation
        controlReconnectJob = scope.launch {
            delay(reconnectDelayMs(controlFailCount))
            synchronized(lock) {
                if (!stopped && generation == expectedGeneration) {
                    controlReconnectJob = null
                    connectControl()
                }
            }
        }
    }

    private fun buildRequirementsQuery(): JSONObject = JSONObject().apply {
        put("type", "requirements_query")
        put("protocol_version", NaviV2Constants.PROTOCOL_VERSION)
        put("timestamp_ms", System.currentTimeMillis())
        put("app_version", APP_VERSION)
        put("catalog_revision", NaviV2Constants.CATALOG_REVISION)
        put("limits", JSONObject().apply {
            put("max_binary_frame_bytes", 0)
            put("max_total_bitrate_kbps", 12000)
        })
        put("streams", JSONArray().apply {
            NaviV2Constants.CATALOG.forEach { (kind, name) ->
                put(JSONObject().apply {
                    put("kind", kind)
                    put("name", name)
                    put("schema_version", 1)
                    put("nullable", true)
                    put("supported", kind == "json")
                    put("supported_params", JSONObject().apply {
                        if (kind == "json") {
                            put("delivery_mode", JSONArray(DELIVERY_MODES.toList()))
                            put("interval_ms", JSONObject().apply {
                                put("min", 200)
                                put("max", 5000)
                                put("default", 1000)
                            })
                            put("stale_timeout_ms", JSONObject().apply {
                                put("min", 1)
                                put("max", Int.MAX_VALUE)
                                put("default", NaviV2Constants.STALE_TIMEOUT_MS)
                            })
                        }
                    })
                })
            }
        })
    }

    private fun handleControlMessage(text: String, attempt: Long, slot: SocketSlot) {
        try {
            val json = JSONObject(text)
            when (json.getString("type")) {
                "subscription_manifest" -> applyManifest(json)
                "protocol_error" -> {
                    logProtocolError(json)
                    // A rejected query will not produce a manifest. One delayed,
                    // generation-scoped restart also handles receiver_busy.
                    if (isCurrent(attempt, slot)) disconnectAndRetry()
                }
                else -> Log.d(TAG, "control message: ${json.optString("type")}")
            }
        } catch (error: Exception) {
            reportError("invalid control message: ${error.message}", error)
            if (isCurrent(attempt, slot)) disconnectAndRetry()
        }
    }

    private fun applyManifest(json: JSONObject) {
        val slot = control ?: return
        val attempt = generation
        val session = validateManifest(json)
        if (currentSession == session) return
        val oldSockets = jsonStreams.values.mapNotNull { it.socket }
        jsonStreams.clear()
        currentSession = session
        streamSequences.clear()
        sendFailures.clear()
        readyNotified = false
        readySinceMs = 0
        oldSockets.forEach { it.cancel() }
        armOpeningTimeout(attempt, slot)
        setState(NaviV2ConnectionState.NEGOTIATING)
        if (!isCurrent(attempt, slot) || currentSession !== session) return
        if (slot.socket?.send(buildManifestApplied(session).toString()) != true) {
            reportError("manifest_applied enqueue failed")
            if (isCurrent(attempt, slot)) disconnectAndRetry()
            return
        }
        session.streams.values.filter { it.kind == "json" && it.enabled }.forEach {
            if (isCurrent(attempt, slot) && currentSession === session) {
                openJsonStream(attempt, slot, session, it)
            }
        }
        notifyReady(attempt, slot, session)
    }

    private fun buildManifestApplied(session: NaviV2Session): JSONObject = JSONObject().apply {
        put("type", "manifest_applied")
        put("protocol_version", NaviV2Constants.PROTOCOL_VERSION)
        put("timestamp_ms", System.currentTimeMillis())
        put("session_id", session.sessionId)
        put("revision", session.revision)
        put("effective_config", JSONArray().apply {
            session.streams.values.forEach { config ->
                put(JSONObject().apply {
                    put("kind", config.kind)
                    put("name", config.name)
                    put("schema_version", config.schemaVersion)
                    put("stream_handle", config.streamHandle)
                    put("enabled", config.enabled)
                    put("params", JSONObject(config.params))
                })
            }
        })
    }

    private fun logProtocolError(json: JSONObject) {
        reportError("protocol_error: code=${json.optString("code")} " +
            "stream=${json.optString("kind")}:${json.optString("name")} " +
            "recoverable=${json.optBoolean("recoverable", true)} " +
            "message=${json.optString("message")}")
    }

    private fun openJsonStream(
        attempt: Long,
        controlSlot: SocketSlot,
        session: NaviV2Session,
        config: StreamConfig
    ) {
        val key = "json:${config.name}"
        val slot = SocketSlot()
        jsonStreams[key] = slot
        fun active(): Boolean = isCurrent(attempt, controlSlot) &&
            currentSession === session && jsonStreams[key] === slot
        try {
            val request = Request.Builder()
                .url("ws://$targetHost:$targetPort/api/navi/ws/v2/json/${session.sessionId}/${config.name}")
                .build()
            val socket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) = synchronized(lock) {
                    if (!active()) {
                        ws.cancel()
                        return@synchronized
                    }
                    slot.socket = ws
                    slot.open = true
                    notifyReady(attempt, controlSlot, session)
                }

                override fun onMessage(ws: WebSocket, text: String) = synchronized(lock) {
                    if (!active()) return@synchronized
                    try {
                        val json = JSONObject(text)
                        if (json.optString("type") == "protocol_error") {
                            logProtocolError(json)
                            if (active()) disconnectAndRetry()
                        }
                    } catch (error: Exception) {
                        reportError("invalid JSON stream response for $key: ${error.message}", error)
                        if (active()) disconnectAndRetry()
                    }
                }

                override fun onClosing(ws: WebSocket, code: Int, reason: String) = synchronized(lock) {
                    if (active()) disconnectAndRetry()
                    ws.close(code, reason)
                    Unit
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) = synchronized(lock) {
                    if (active()) disconnectAndRetry()
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) = synchronized(lock) {
                    if (active()) {
                        reportError("JSON stream failed for $key: ${t.message}", t)
                        disconnectAndRetry()
                    }
                }
            })
            if (active()) slot.socket = socket else socket.cancel()
        } catch (error: Exception) {
            if (active()) {
                reportError("JSON stream opening failed for $key: ${error.message}", error)
                disconnectAndRetry()
            }
        }
    }

    private fun notifyReady(attempt: Long, slot: SocketSlot, session: NaviV2Session) {
        if (!isCurrent(attempt, slot) || currentSession !== session ||
            readyNotified || !isReadyLocked()) return
        readyNotified = true
        readySinceMs = System.currentTimeMillis()
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = null
        setState(NaviV2ConnectionState.CONNECTED)
        if (isCurrent(attempt, slot) && currentSession === session) onSessionReady?.invoke(session)
    }

    /** Returns actual OkHttp enqueue success, not merely presence of a session. */
    fun sendJsonData(name: String, present: Boolean, value: Any?, reason: String? = null,
        sourceTimestampMs: Long = System.currentTimeMillis()): Boolean =
        synchronized(lock) {
            val session = currentSession ?: return@synchronized false
            val key = "json:$name"
            val config = session.streams[key] ?: return@synchronized false
            val slot = jsonStreams[key] ?: return@synchronized false
            if (stopped || control?.open != true || !config.enabled || !slot.open) {
                return@synchronized false
            }
            val envelope = JsonEnvelope(
                sessionId = session.sessionId,
                name = name,
                manifestRevision = session.revision,
                streamHandle = config.streamHandle,
                schemaVersion = config.schemaVersion,
                sequence = nextSequence("json", name),
                sourceTimestampMs = sourceTimestampMs,
                present = present,
                value = value,
                reason = reason
            )
            val message = try {
                envelope.toJson().toString()
            } catch (error: Exception) {
                reportError("JSON encoding failed for $key: ${error.message}", error)
                if (currentSession === session && jsonStreams[key] === slot) {
                    disconnectAndRetry()
                    sendFailures.add(key)
                }
                return@synchronized false
            }
            val socket = slot.socket ?: return@synchronized false
            enqueueJson(key, socket, message)
        }

    private fun enqueueJson(key: String, socket: WebSocket, message: String): Boolean {
        val accepted = try {
            socket.send(message)
        } catch (error: Exception) {
            reportError("JSON enqueue failed for $key: ${error.message}", error)
            false
        }
        if (!accepted) {
            reportError("JSON enqueue rejected for $key")
            if (jsonStreams[key]?.socket === socket) {
                disconnectAndRetry()
                sendFailures.add(key)
            }
        }
        return accepted
    }

    fun getTargetHost(): String = synchronized(lock) { targetHost }

    fun debugSnapshot(): JSONObject = synchronized(lock) {
        JSONObject().apply {
            put("state", _connectionState.value.name)
            put("target", targetHost.ifEmpty { "-" })
            put("port", targetPort)
            put("stopped", stopped)
            put("ready", isReadyLocked())
            put("discoveryReceived", discoveryReceived)
            put("controlFailCount", controlFailCount)
            val session = currentSession
            put("sessionId", session?.sessionId ?: JSONObject.NULL)
            if (session != null) {
                put("revision", session.revision)
                put("streamCount", session.streams.size)
            }
            put("sequences", JSONObject(streamSequences.toMap()))
            put("streams", JSONObject().apply {
                NaviV2Constants.JSON_NAMES.forEach { name ->
                    val key = "json:$name"
                    put(name, when {
                        key in sendFailures -> "send_failed"
                        jsonStreams[key]?.open == true -> "connected"
                        key in jsonStreams -> "connecting"
                        else -> "closed"
                    })
                }
            })
        }
    }
}
