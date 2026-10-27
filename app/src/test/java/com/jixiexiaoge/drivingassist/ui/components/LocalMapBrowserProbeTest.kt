package com.jixiexiaoge.drivingassist.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * 本地投屏服务探测（7.9.60 内嵌浏览器的第一道判定）。
 *
 * 开放端口不等于投屏服务，必须核对有界健康响应。
 */
class LocalMapBrowserProbeTest {
    /**
     * 投屏页 URL 必须带 `embedded=1`。
     *
     * 不带时页面走 `/stream` 的 MJPEG，Android WebView（Chromium）不支持
     * `multipart/x-mixed-replace` → 画面永远黑屏（7.9.62 实际踩过）。
     */
    @Test
    fun projectionUrlRequestsEmbeddedJpegMode() {
        assertEquals("http://127.0.0.1:8081/?embedded=1", mapProjectionUrl(8081))
        assertEquals("http://127.0.0.1:8080/?embedded=1", mapProjectionUrl(8080))
    }

    @Test
    fun onlyRunningProjectionHealthCountsAsAlive() {
        checkHealth("""{"service":"navipilot-projection","schema_version":1,"running":true}""", true)
    }

    @Test
    fun unrelatedStoppedMalformedAndOversizedResponsesAreRejected() {
        checkHealth("""{"error":"not found"}""", false, 404)
        for (body in listOf(
            """{"running":true}""",
            """{"service":"other","schema_version":1,"running":true}""",
            """{"service":"navipilot-projection","schema_version":1,"running":false}""",
            "not json", "x".repeat(8193),
        )) checkHealth(body, false)
        checkHealth("redirect", false, 302)
    }

    private fun checkHealth(body: String, expected: Boolean, code: Int = 200) {
        ServerSocket(0).use { server ->
            val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
            val responder = thread(isDaemon = true) {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 2000
                        val reader = socket.getInputStream().bufferedReader()
                        assertEquals("GET /health HTTP/1.1", reader.readLine())
                        while (!reader.readLine().isNullOrEmpty()) Unit
                        val bytes = body.toByteArray()
                        val redirect = if (code == 302) "Location: /health\r\n" else ""
                        val header = "HTTP/1.1 $code Test\r\nContent-Length: ${bytes.size}\r\n" +
                            "${redirect}Connection: close\r\n\r\n"
                        socket.getOutputStream().apply {
                            write(header.toByteArray(Charsets.US_ASCII))
                            write(bytes)
                            flush()
                        }
                    }
                } catch (error: Throwable) {
                    failure.set(error)
                }
            }
            assertEquals(expected, probeLocalMapService(server.localPort))
            responder.join(3000)
            assertEquals(null, failure.get())
        }
    }
}
