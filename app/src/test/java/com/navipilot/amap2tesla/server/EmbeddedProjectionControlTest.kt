package com.navipilot.amap2tesla.server

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.common.truth.Truth.assertThat
import com.navipilot.amap2tesla.video.LatestFrameStore
import com.jixiexiaoge.drivingassist.data.CompactFrame
import com.jixiexiaoge.drivingassist.data.CompactServiceSample
import com.jixiexiaoge.drivingassist.data.CompactTelemetry
import com.jixiexiaoge.drivingassist.data.DashboardStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class EmbeddedProjectionControlTest {
    private lateinit var server: LocalProjectionServer
    private lateinit var frameStore: LatestFrameStore
    private val sink = mockk<ControlSink>(relaxed = true)
    private val client = OkHttpClient()
    private val peers = mutableListOf<Peer>()

    @Before
    fun setUp() {
        mockkStatic(Log::class, SystemClock::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any(), any()) } returns 0
        every { SystemClock.elapsedRealtime() } answers { System.nanoTime() / 1_000_000 }
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        every { sink.onViewport(any(), any()) } returns true
        every { sink.onPointer(any(), any(), any(), any(), any()) } returns true
        every { sink.onKey(any()) } returns true
        frameStore = LatestFrameStore()
        server = LocalProjectionServer.startWithFallback(context, frameStore, sink, firstPort = 0, attempts = 1)
    }

    @After
    fun tearDown() {
        DashboardStore.clear()
        peers.forEach { it.socket.close(1000, "Test completed") }
        peers.forEach { it.closed.await(5, TimeUnit.SECONDS) }
        if (::server.isInitialized) server.stop()
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
        unmockkStatic(Log::class, SystemClock::class)
    }

    @Test
    fun `health identifies this projection service without assuming a port`() {
        client.newCall(Request.Builder().url("http://127.0.0.1:${server.listeningPort}/health")
            .build()).execute().use {
            assertThat(it.code).isEqualTo(200)
            val json = JSONObject(checkNotNull(it.body).string())
            assertThat(json.getString("service")).isEqualTo("navipilot-projection")
            assertThat(json.getInt("schema_version")).isEqualTo(1)
            assertThat(json.has("running")).isTrue()
        }
    }

    @Test
    fun `dashboard endpoint is read only with explicit keys actual TTL and no control side effects`() {
        val now = System.nanoTime() / 1_000_000
        DashboardStore.telemetry = CompactTelemetry(mapOf("carState" to CompactServiceSample(
            CompactFrame("carState", 1, mapOf("vEgo" to 10f)), now, now + 500,
        )), now)
        val url = "http://127.0.0.1:${server.listeningPort}/dashboard"
        client.newCall(Request.Builder().url(url).build()).execute().use {
            assertThat(it.code).isEqualTo(200)
            assertThat(it.header("Cache-Control")).contains("no-store")
            val json = JSONObject(checkNotNull(it.body).string())
            assertThat(json.getInt("schemaVersion")).isEqualTo(1)
            val panels = json.getJSONArray("panels")
            assertThat((0 until panels.length()).map { index -> panels.getJSONObject(index).getString("source") }
                .all { source -> source.startsWith("7000/") }).isTrue()
            assertThat((0 until panels.length()).map { index -> panels.getJSONObject(index).getString("id") })
                .doesNotContain("tesla")
            val vehicle = json.getJSONArray("panels").getJSONObject(0)
            assertThat(vehicle.getString("id")).isEqualTo("vehicle")
            assertThat(vehicle.getLong("remainingMs")).isAtMost(500)
            assertThat(vehicle.getJSONArray("metrics").getJSONObject(0).getString("value")).isEqualTo("36.0")
        }
        client.newCall(Request.Builder().url(url).delete().build()).execute().use {
            assertThat(it.code).isEqualTo(405)
        }
        verify(exactly = 0) { sink.onViewport(any(), any()) }
        verify(exactly = 0) { sink.onKey(any()) }
        verify(exactly = 0) { sink.onPointer(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `frame endpoint explicitly reports when no frame is available`() {
        client.newCall(Request.Builder().url("http://127.0.0.1:${server.listeningPort}/frame").build()).execute().use {
            assertThat(it.code).isEqualTo(204)
            assertThat(it.body?.bytes()).isEmpty()
            assertThat(it.header("Cache-Control")).contains("no-store")
        }
    }

    @Test
    fun `frame endpoint returns the latest JPEG without multipart wrapping`() {
        val first = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 0xFF.toByte(), 0xD9.toByte())
        val latest = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 2, 0xFF.toByte(), 0xD9.toByte())
        frameStore.publish(first)
        frameStore.publish(latest)
        client.newCall(Request.Builder().url("http://127.0.0.1:${server.listeningPort}/frame").build()).execute().use {
            assertThat(it.code).isEqualTo(200)
            assertThat(it.header("Content-Type")).isEqualTo("image/jpeg")
            assertThat(it.header("Content-Length")).isEqualTo(latest.size.toString())
            assertThat(it.header("X-Frame-Sequence")).isEqualTo("2")
            assertThat(it.header("Cache-Control")).contains("no-store")
            assertThat(it.body?.bytes()).isEqualTo(latest)
        }
    }

    @Test
    fun `snapshot and MJPEG endpoints serve identical encoded frame bytes`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 7, 0xFF.toByte(), 0xD9.toByte())
        frameStore.publish(jpeg)
        client.newCall(Request.Builder().url("http://127.0.0.1:${server.listeningPort}/frame").build()).execute().use {
            assertThat(it.body?.bytes()).isEqualTo(jpeg)
        }
        client.newCall(Request.Builder().url("http://127.0.0.1:${server.listeningPort}/stream").build()).execute().use {
            assertThat(it.header("Content-Type")).contains("multipart/x-mixed-replace")
            val source = checkNotNull(it.body).source()
            assertThat(source.readUtf8LineStrict()).isEqualTo("--frame")
            assertThat(source.readUtf8LineStrict()).isEqualTo("Content-Type: image/jpeg")
            assertThat(source.readUtf8LineStrict()).isEqualTo("Content-Length: ${jpeg.size}")
            assertThat(source.readUtf8LineStrict()).isEmpty()
            assertThat(source.readByteArray(jpeg.size.toLong())).isEqualTo(jpeg)
        }
    }

    @Test
    fun `clearing frames prevents the snapshot endpoint from serving stale video`() {
        frameStore.publish(byteArrayOf(1, 2, 3))
        frameStore.clear()
        client.newCall(Request.Builder().url("http://127.0.0.1:${server.listeningPort}/frame").build()).execute().use {
            assertThat(it.code).isEqualTo(204)
        }
    }

    @Test
    fun `embedded browser keeps touch and key control without taking viewport ownership`() {
        val embedded = peer("?embedded=1")
        embedded.socket.send("""{"type":"viewport","width":400,"height":300}""")
        val declined = embedded.next()
        assertThat(declined.getBoolean("primary")).isFalse()
        assertThat(declined.getBoolean("ok")).isFalse()
        verify(exactly = 0) { sink.onViewport(any(), any()) }

        embedded.socket.send("""{"type":"pointer","action":0,"x":0.4,"y":0.5,"pointerId":0}""")
        assertThat(embedded.next().getBoolean("ok")).isTrue()
        verify(exactly = 1) { sink.onPointer(any(), 0, 0.4f, 0.5f, 0) }
        embedded.socket.send("""{"type":"key","keyCode":4}""")
        assertThat(embedded.next().getBoolean("ok")).isTrue()
        verify(exactly = 1) { sink.onKey(4) }

        val carBrowser = peer("")
        carBrowser.socket.send("""{"type":"viewport","width":1180,"height":920}""")
        val accepted = carBrowser.next()
        assertThat(accepted.getBoolean("primary")).isTrue()
        assertThat(accepted.getBoolean("ok")).isTrue()
        verify(exactly = 1) { sink.onViewport(1180, 920) }

        embedded.socket.send("""{"type":"viewport","width":768,"height":576}""")
        assertThat(embedded.next().getBoolean("primary")).isFalse()
        verify(exactly = 1) { sink.onViewport(any(), any()) }
    }

    private fun peer(query: String): Peer {
        val peer = Peer()
        peer.socket = client.newWebSocket(
            Request.Builder().url("ws://127.0.0.1:${server.listeningPort}/control$query").build(),
            peer,
        )
        peers.add(peer)
        assertThat(peer.next().getString("type")).isEqualTo("ready")
        return peer
    }

    private class Peer : WebSocketListener() {
        lateinit var socket: WebSocket
        val closed = CountDownLatch(1)
        private val messages = LinkedBlockingQueue<String>()

        override fun onMessage(webSocket: WebSocket, text: String) {
            messages.add(text)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            closed.countDown()
        }

        override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
            messages.add(JSONObject().put("type", "failure").put("reason", error.toString()).toString())
            closed.countDown()
        }

        fun next(): JSONObject = JSONObject(
            messages.poll(5, TimeUnit.SECONDS) ?: throw IOException("Timed out waiting for projection control reply"),
        )
    }
}
