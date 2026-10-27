package com.navipilot.amap2tesla.server

import android.content.Context
import android.util.Log
import com.navipilot.amap2tesla.ConnectionMode
import com.navipilot.amap2tesla.ProjectionState
import com.navipilot.amap2tesla.network.TeslaVirtualIpService
import com.navipilot.amap2tesla.video.LatestFrameStore
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import fi.iki.elonen.NanoWSD.WebSocket
import fi.iki.elonen.NanoWSD.WebSocketFrame
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class LocalProjectionServer private constructor(
    private val context: Context,
    val activePort: Int,
    private val frameStore: LatestFrameStore,
    private val controlSink: ControlSink
) : NanoWSD("0.0.0.0", activePort) {
    private val nextGestureId = AtomicInteger(1)
    private val controlClients = AtomicInteger(0)
    private val viewportOwner = AtomicInteger(0)

    override fun serveHttp(session: IHTTPSession): Response {
        return when (session.uri) {
            "/dashboard" -> {
                if (session.method != Method.GET) {
                    newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain", "Read-only endpoint")
                } else {
                    val snapshot = com.jixiexiaoge.drivingassist.data.DashboardStore.snapshot(
                        System.nanoTime() / 1_000_000)
                    newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", snapshot.toWireJson())
                        .apply { addHeader("Cache-Control", "no-store, max-age=0") }
                }
            }
            "/dashboard.css", "/dashboard.js" -> {
                newChunkedResponse(Response.Status.OK,
                    if (session.uri.endsWith(".css")) "text/css; charset=utf-8" else "application/javascript; charset=utf-8",
                    context.assets.open(session.uri.removePrefix("/")))
                    .apply { addHeader("Cache-Control", "no-store") }
            }
            "/", "/index.html" -> {
                val html = context.assets.open("amap2tesla_player.html")
                newChunkedResponse(Response.Status.OK, "text/html; charset=utf-8", html).apply {
                    addHeader("Cache-Control", "no-store, no-cache, must-revalidate")
                    addHeader("Pragma", "no-cache")
                }
            }
            "/stream" -> {
                newChunkedResponse(
                    Response.Status.OK,
                    "multipart/x-mixed-replace; boundary=frame",
                    MjpegInputStream(frameStore)
                ).apply {
                    addHeader("Cache-Control", "no-store, no-cache, must-revalidate")
                    addHeader("Pragma", "no-cache")
                    addHeader("Connection", "close")
                }
            }
            "/frame" -> {
                val snapshot = frameStore.snapshot()
                val response = if (snapshot == null) {
                    newFixedLengthResponse(Response.Status.NO_CONTENT, "image/jpeg", "")
                } else {
                    newFixedLengthResponse(
                        Response.Status.OK, "image/jpeg",
                        snapshot.bytes.inputStream(), snapshot.bytes.size.toLong(),
                    ).apply {
                        addHeader("X-Frame-Sequence", snapshot.sequence.toString())
                    }
                }
                response.apply { addHeader("Cache-Control", "no-store, no-cache, must-revalidate") }
            }
            "/health" -> {
                val metrics = frameStore.metrics()
                val json = JSONObject()
                    .put("service", "navipilot-projection")
                    .put("schema_version", 1)
                    .put("running", ProjectionState.running)
                    .put("status", ProjectionState.status)
                    .put("displayId", ProjectionState.displayId ?: -1)
                    .put("width", ProjectionState.width)
                    .put("height", ProjectionState.height)
                    .put("densityDpi", ProjectionState.densityDpi)
                    .put("targetFps", ProjectionState.targetFps)
                    .put("trialMode", ProjectionState.trialMode)
                    .put("fps", metrics.encodedFps.toDouble())
                    .put("frameSequence", metrics.sequence)
                    .put("frameAgeMs", metrics.frameAgeMillis)
                    .put("controlClients", controlClients.get())
                    .toString()
                newFixedLengthResponse(Response.Status.OK, "application/json", json)
            }
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        }
    }

    override fun openWebSocket(handshake: IHTTPSession): WebSocket {
        return ControlWebSocket(handshake, nextGestureId.getAndIncrement())
    }

    fun accessUrl(mode: ConnectionMode): String {
        val host = if (mode == ConnectionMode.TESLA) {
            TeslaVirtualIpService.CLIENT_ADDRESS
        } else {
            findLocalIpv4()
        }
        return "http://$host:$activePort"
    }

    private inner class ControlWebSocket(
        handshake: IHTTPSession,
        private val gestureId: Int
    ) : WebSocket(handshake) {
        private val embeddedPreview = handshake.parameters["embedded"]?.firstOrNull() == "1"

        override fun onOpen() {
            controlClients.incrementAndGet()
            if (!embeddedPreview) viewportOwner.compareAndSet(0, gestureId)
            send("""{"type":"ready","gestureId":$gestureId}""")
        }

        override fun onClose(
            code: WebSocketFrame.CloseCode?,
            reason: String?,
            initiatedByRemote: Boolean
        ) {
            Log.i(
                TAG,
                "Control socket closed gesture=$gestureId code=$code " +
                    "remote=$initiatedByRemote reason=${reason.orEmpty()}"
            )
            controlSink.onCancel(gestureId)
            viewportOwner.compareAndSet(gestureId, 0)
            controlClients.updateAndGet {
                (it - 1).coerceAtLeast(0)
            }
        }

        override fun onMessage(message: WebSocketFrame) {
            val response = try {
                val json = JSONObject(message.textPayload)
                if (json.optString("type") == "ping") {
                    send("""{"type":"pong"}""")
                    return
                }
                val type = json.optString("type")
                if (type == "viewport") {
                    val isOwner = !embeddedPreview && (viewportOwner.get() == gestureId ||
                        viewportOwner.compareAndSet(0, gestureId))
                    val accepted = isOwner && controlSink.onViewport(
                        json.getInt("width"),
                        json.getInt("height")
                    )
                    send(
                        JSONObject()
                            .put("type", "viewportResult")
                            .put("primary", isOwner)
                            .put("ok", accepted)
                            .toString()
                    )
                    return
                }
                val accepted = when (type) {
                    "pointer" -> {
                        val action = json.getInt("action")
                        val x = json.getDouble("x").toFloat()
                        val y = json.getDouble("y").toFloat()
                        val pointerId = json.optInt("pointerId", 0)
                        if (action !in 0..3 || !x.isFinite() || !y.isFinite() ||
                            x !in 0f..1f || y !in 0f..1f
                        ) {
                            throw IllegalArgumentException("Invalid pointer event")
                        }
                        controlSink.onPointer(
                            gestureId,
                            action,
                            x,
                            y,
                            pointerId
                        )
                    }
                    "key" -> controlSink.onKey(json.getInt("keyCode"))
                    else -> throw IllegalArgumentException("Unsupported control message")
                }
                JSONObject()
                    .put("type", "controlResult")
                    .put("ok", accepted)
                    .toString()
            } catch (error: Throwable) {
                JSONObject()
                    .put("type", "error")
                    .put("reason", error.message ?: error.javaClass.simpleName)
                    .toString()
            }
            send(response)
        }

        override fun onPong(pong: WebSocketFrame) = Unit

        override fun onException(exception: IOException) {
            Log.w(TAG, "Control socket exception gesture=$gestureId", exception)
            controlSink.onCancel(gestureId)
        }
    }

    companion object {
        private const val TAG = "Amap2TeslaServer"
        private const val SERVER_READ_TIMEOUT_MS = 0

        fun startWithFallback(
            context: Context,
            frameStore: LatestFrameStore,
            controlSink: ControlSink,
            firstPort: Int = 8080,
            attempts: Int = 20
        ): LocalProjectionServer {
            var lastError: IOException? = null
            for (port in firstPort until firstPort + attempts) {
                val server = LocalProjectionServer(
                    context.applicationContext,
                    port,
                    frameStore,
                    controlSink
                )
                try {
                    server.start(SERVER_READ_TIMEOUT_MS, false)
                    return server
                } catch (error: IOException) {
                    lastError = error
                    runCatching { server.stop() }
                }
            }

            throw lastError ?: IOException("没有可用的本地服务端口")
        }

        private fun findLocalIpv4(): String {
            val addresses = Collections.list(NetworkInterface.getNetworkInterfaces())
                .filter { it.isUp && !it.isLoopback }
                .flatMap { Collections.list(it.inetAddresses) }
                .filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress }

            return addresses.firstOrNull { it.isSiteLocalAddress }?.hostAddress
                ?: addresses.firstOrNull()?.hostAddress
                ?: "127.0.0.1"
        }
    }
}

interface ControlSink {
    fun onPointer(
        gestureId: Int,
        action: Int,
        x: Float,
        y: Float,
        pointerId: Int
    ): Boolean

    fun onCancel(gestureId: Int)

    fun onKey(keyCode: Int): Boolean

    fun onViewport(width: Int, height: Int): Boolean
}

private class MjpegInputStream(
    private val frameStore: LatestFrameStore
) : InputStream() {
    private var sequence = -1L
    private var current = ByteArray(0)
    private var offset = 0
    @Volatile
    private var closed = false

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) == 1) one[0].toInt() and 0xff else -1
    }

    override fun read(buffer: ByteArray, off: Int, len: Int): Int {
        if (closed) return -1
        while (offset >= current.size) {
            val snapshot = frameStore.awaitAfter(sequence, 5_000)
            if (snapshot == null) {
                // 无帧 / 超时：退避一小段再重试，绝不空转（否则该连接的 NanoHTTPD 线程会独占一个核）
                if (closed) return -1
                try {
                    Thread.sleep(50)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return -1
                }
                continue
            }
            sequence = snapshot.sequence
            val header = (
                "--frame\r\n" +
                    "Content-Type: image/jpeg\r\n" +
                    "Content-Length: ${snapshot.bytes.size}\r\n\r\n"
                ).toByteArray(Charsets.US_ASCII)
            val trailer = "\r\n".toByteArray(Charsets.US_ASCII)
            current = ByteArray(header.size + snapshot.bytes.size + trailer.size)
            header.copyInto(current, 0)
            snapshot.bytes.copyInto(current, header.size)
            trailer.copyInto(current, header.size + snapshot.bytes.size)
            offset = 0
        }
        val count = minOf(len, current.size - offset)
        current.copyInto(buffer, off, offset, offset + count)
        offset += count
        return count
    }

    override fun close() {
        closed = true
    }
}
