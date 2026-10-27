package com.jixiexiaoge.drivingassist.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * 7711 协议端到端：本机起一个假的 `xiaoge_data.py` 广播端，验证
 * 长度前缀分帧、JSON 字段解析、断连回落。
 *
 * 这条链路之前完全没有测试——App 里连不上车端时界面上只表现为「没有灯」，
 * 分帧写错也看不出来，所以在这里用真实 socket 固定住。
 */
@RunWith(AndroidJUnit4::class)
class XiaogeBridgeClientTest {
    @Test
    fun timedOutPartialHeaderIsDiscardedAndNewConnectionStartsAtARealFrameBoundary() = runBlocking {
        val server = ServerSocket(XiaogeBridgeClient.PORT)
        val stop = java.util.concurrent.CountDownLatch(1)
        var peer: Socket? = null
        val writer = thread(isDaemon = true) {
            server.accept().use { first ->
                val out = first.getOutputStream()
                out.write(packet(frame("38.5", 1)))
                out.write(byteArrayOf(0, 0))
                out.flush()
                // A partial header must not let the next connection inherit its offset.
                Thread.sleep(1800)
            }
            server.accept().use { second ->
                peer = second
                second.getOutputStream().apply {
                    write(packet(frame("12.0", 2, sequence = 1)))
                    flush()
                }
                stop.await(8, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
        val client = XiaogeBridgeClient()
        try {
            client.connect("127.0.0.1")
            withTimeout(5000) { client.world.filterNotNull().first { it.trafficLightColor == 1 } }
            withTimeout(3000) {
                while (client.world.value != null || client.connected.value) kotlinx.coroutines.delay(20)
            }
            assertFalse(client.connected.value)
            val recovered = withTimeout(7000) {
                client.world.filterNotNull().first { it.trafficLightColor == 2 }
            }
            assertEquals(12f, recovered.stopLineDistM ?: 0f, 0.01f)
            assertTrue(client.connected.value)
        } finally {
            client.close()
            stop.countDown()
            peer?.close()
            server.close()
            writer.join(1000)
        }
    }

    private fun packet(json: String): ByteArray {
        val body = json.toByteArray(Charsets.UTF_8)
        val head = byteArrayOf(
            ((body.size ushr 24) and 0xFF).toByte(),
            ((body.size ushr 16) and 0xFF).toByte(),
            ((body.size ushr 8) and 0xFF).toByte(),
            (body.size and 0xFF).toByte(),
        )
        return head + body
    }

    private fun frame(stopLine: String, color: Int, sequence: Int = 3, timestamp: Double = sequence.toDouble()) =
        """{"version":1,"sequence":$sequence,"timestamp":$timestamp,"ip":"127.0.0.1",
           "data":{"carState":{"stopLineDist":$stopLine,"trafficLightColor":$color}}}"""

    @Test
    fun framedJsonUpdatesWorldAndDisconnectClearsIt() = runBlocking {
        val server = ServerSocket(XiaogeBridgeClient.PORT)
        var peer: Socket? = null
        val writer = thread(isDaemon = true) {
            val accepted = server.accept()
            peer = accepted
            val out: OutputStream = accepted.getOutputStream()
            // 两帧分开写：中间留 600 ms，才能分别观察「第一帧」和「第二帧覆盖第一帧」
            out.write(packet(frame("38.5", 1)))
            out.flush()
            Thread.sleep(600)
            out.write(packet(frame("12.0", 2, sequence = 4)))
            out.flush()
            Thread.sleep(800)
            accepted.close()
        }
        val client = XiaogeBridgeClient()
        try {
            client.connect("127.0.0.1")
            // 第一帧
            val world = withTimeout(8_000) {
                client.world.filterNotNull().first { it.trafficLightColor == 1 }
            }
            assertEquals(38.5f, world.stopLineDistM ?: 0f, 0.01f)
            assertTrue(world.lampVisible)

            // 第二帧覆盖第一帧（说明按长度前缀分帧正确，而不是被第一帧搞坏连接）
            val second = withTimeout(8_000) {
                client.world.filterNotNull().first { it.trafficLightColor == 2 }
            }
            assertEquals(12.0f, second.stopLineDistM ?: 0f, 0.01f)

            // 对端断开后必须回落成 null，界面不会再显示上一帧的灯
            withTimeout(10_000) {
                while (client.world.value != null) kotlinx.coroutines.delay(50)
            }
            assertFalse(client.connected.value)
        } finally {
            client.close()
            runCatching { peer?.close() }
            runCatching { writer.join(1_000) }
            runCatching { server.close() }
        }
    }

    @Test
    fun malformedFrameLengthDoesNotCrashAndDropsConnection() = runBlocking {
        val server = ServerSocket(XiaogeBridgeClient.PORT)
        val sent = java.util.concurrent.CountDownLatch(1)
        val writer = thread(isDaemon = true) {
            val accepted = server.accept()
            // 0 长度帧：协议上非法，客户端应丢弃并重连，而不是无限等待
            accepted.getOutputStream().write(byteArrayOf(0, 0, 0, 0))
            accepted.getOutputStream().flush()
            sent.countDown()
            Thread.sleep(800)
            runCatching { accepted.close() }
        }
        val client = XiaogeBridgeClient()
        try {
            client.connect("127.0.0.1")
            assertTrue(sent.await(5, java.util.concurrent.TimeUnit.SECONDS))
            withTimeout(6_000) {
                while (client.connected.value) kotlinx.coroutines.delay(50)
            }
            // 从未产生任何世界量
            assertEquals(null, client.world.value)
        } finally {
            client.close()
            runCatching { writer.join(1_000) }
            runCatching { server.close() }
        }
    }

    @Test
    fun worldVisibilityMirrorsDashpilotThresholds() {
        val near = XiaogeBridgeClient.World(20f, 3)
        assertTrue(near.lampVisible)
        assertTrue(near.stopLineVisible)
        val far = XiaogeBridgeClient.World(200f, 2)
        assertTrue(far.lampVisible)
        assertFalse(far.stopLineVisible)
        assertEquals(2, far.trafficLightColor)
    }

    @Test
    fun frozenSourceTimestampCannotRenewWorldAndMalformedInputClearsIt() = runBlocking {
        val server = ServerSocket(XiaogeBridgeClient.PORT)
        val release = java.util.concurrent.CountDownLatch(1)
        var peer: Socket? = null
        val writer = thread(isDaemon = true) {
            server.accept().use { accepted ->
                peer = accepted
                val out = accepted.getOutputStream()
                out.write(packet(frame("38.5", 1)))
                out.flush()
                release.await(5, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
        val client = XiaogeBridgeClient()
        try {
            client.connect("127.0.0.1")
            val first = withTimeout(5_000) { client.world.filterNotNull().first() }
            val out = requireNotNull(peer).getOutputStream()
            repeat(18) { index ->
                out.write(packet(frame("12.0", 2, index + 4, 3.0)))
                out.flush()
                kotlinx.coroutines.delay(100)
            }
            assertEquals(null, client.world.value)
            assertTrue(client.connected.value)
            out.write(packet(frame("12.0", 2, 30, 4.0)))
            out.flush()
            val recovered = withTimeout(3_000) {
                client.world.filterNotNull().first { it.trafficLightColor == 2 }
            }
            assertTrue(recovered.receivedAtMs > first.receivedAtMs)
            out.write(packet("""{"data":{}}"""))
            out.flush()
            withTimeout(3_000) {
                while (client.world.value != null || client.connected.value) kotlinx.coroutines.delay(20)
            }
        } finally {
            client.close()
            release.countDown()
            peer?.close()
            writer.join(1_000)
            server.close()
        }
    }
}
