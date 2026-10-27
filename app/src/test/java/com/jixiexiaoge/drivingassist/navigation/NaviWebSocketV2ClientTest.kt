package com.jixiexiaoge.drivingassist.navigation

import android.util.Log
import com.google.common.truth.Truth.assertThat
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class NaviWebSocketV2ClientTest {
    @Before
    fun mockAndroidLog() {
        mockkStatic(Log::class)
        every { Log.e(any(), any(), any()) } returns 0
    }

    @After
    fun cleanup() {
        unmockkAll()
    }

    @Test
    fun `backoff is safe for zero negative and large failure counts`() {
        assertThat(NaviWebSocketV2Client.reconnectDelayMs(Int.MIN_VALUE)).isEqualTo(2000)
        assertThat(NaviWebSocketV2Client.reconnectDelayMs(0)).isEqualTo(2000)
        assertThat(NaviWebSocketV2Client.reconnectDelayMs(1)).isEqualTo(2000)
        assertThat(NaviWebSocketV2Client.reconnectDelayMs(2)).isEqualTo(4000)
        assertThat(NaviWebSocketV2Client.reconnectDelayMs(Int.MAX_VALUE)).isEqualTo(30000)
    }

    @Test
    fun `manifest preserves negotiated JSON settings and disables unsupported media`() {
        val session = NaviWebSocketV2Client.validateManifest(manifest())

        assertThat(session.streams).hasSize(NaviV2Constants.CATALOG.size)
        session.streams.values.forEach {
            assertThat(it.enabled).isEqualTo(it.kind == "json")
        }
        assertThat(session.streams["json:vehicle"]!!.params).containsExactly(
            "delivery_mode", "periodic", "interval_ms", 600, "stale_timeout_ms", 2500
        )
    }

    @Test
    fun `manifest rejects invalid protocol session revision catalog and handles`() {
        val entries = streamEntries()
        val invalid = listOf(
            manifest(protocolVersion = 1),
            manifest(protocolVersion = "2"),
            manifest(sessionId = ""),
            manifest(sessionId = "session/path"),
            manifest(revision = 0),
            manifest(revision = 1.5),
            manifest(entries = entries.dropLast(1)),
            manifest(entries = entries + entries.first()),
            manifest(entries = entries.toMutableList().apply { this[1] = this[0] }),
            manifest(entryOverrides = mapOf("stream_handle" to 0)),
            manifest(entryOverrides = mapOf("stream_handle" to 65536)),
            manifest(entryOverrides = mapOf("schema_version" to 2)),
            manifest(entryOverrides = mapOf("enabled" to "true")),
            manifest(entryOverrides = mapOf("name" to "unknown"))
        )
        invalid.forEach { assertRejected(it) }
        assertRejected(json(emptyMap()))
        assertRejected(json(mapOf(
            "type" to "subscription_manifest", "protocol_version" to 2,
            "session_id" to 123, "revision" to 1, "streams" to array(entries)
        )))
        val duplicateHandle = streamEntries().toMutableList()
        duplicateHandle[1] = streamEntry(NaviV2Constants.CATALOG[1], 1)
        assertRejected(manifest(entries = duplicateHandle))
    }

    @Test
    fun `manifest rejects unsupported JSON delivery settings`() {
        listOf(
            mapOf("delivery_mode" to "unknown", "interval_ms" to 1000, "stale_timeout_ms" to 10000),
            mapOf("delivery_mode" to "on_change", "interval_ms" to 199, "stale_timeout_ms" to 10000),
            mapOf("delivery_mode" to "on_change", "interval_ms" to 5001, "stale_timeout_ms" to 10000),
            mapOf("delivery_mode" to "on_change", "interval_ms" to 1000, "stale_timeout_ms" to 0),
            mapOf("delivery_mode" to "on_change", "interval_ms" to "1000", "stale_timeout_ms" to 10000)
        ).forEach { params ->
            assertRejected(manifest(entryOverrides = mapOf("params" to json(params))))
        }
    }

    @Test
    fun `start reserves connection synchronously and stop rejects delayed callbacks`() = runTest {
        val transport = Transport()
        val client = NaviWebSocketV2Client(this, transport.client)
        client.setTarget("127.0.0.1")
        client.start()
        client.start()
        assertThat(transport.connections).hasSize(1)
        assertThat(client.isReady()).isFalse()
        assertThat(client.isStreamReady("vehicle")).isFalse()

        val old = transport.connections.single()
        client.stop()
        old.listener.onOpen(old.socket, mockk<Response>())
        old.listener.onFailure(old.socket, IOException("late failure"), null)
        old.listener.onClosed(old.socket, 1000, "late close")
        advanceTimeBy(60000)
        runCurrent()

        assertThat(transport.connections).hasSize(1)
        assertThat(client.getSession()).isNull()
        assertThat(client.connectionState.value).isEqualTo(NaviV2ConnectionState.DISCONNECTED)
        verify(atLeast = 1) { old.socket.cancel() }
    }

    @Test
    fun `same target continues reconnecting after discovery failure threshold`() = runTest {
        val transport = Transport()
        val client = NaviWebSocketV2Client(this, transport.client)
        client.setTarget("127.0.0.1")
        client.start()
        repeat(5) { index ->
            val old = transport.connections.last()
            old.listener.onFailure(old.socket, IOException("unavailable"), null)
            client.setTarget("127.0.0.1")
            val wait = NaviWebSocketV2Client.reconnectDelayMs(index + 1)
            advanceTimeBy(wait - 1)
            runCurrent()
            assertThat(transport.connections).hasSize(index + 1)
            advanceTimeBy(1)
            runCurrent()
            assertThat(transport.connections).hasSize(index + 2)
        }
        assertThat(client.getTargetHost()).isEqualTo("127.0.0.1")
        client.stop()
    }

    @Test
    fun `old socket failure cannot tear down a replacement attempt`() = runTest {
        val transport = Transport()
        val client = NaviWebSocketV2Client(this, transport.client)
        client.setTarget("127.0.0.1")
        client.start()
        val old = transport.connections.single()
        client.setTarget("127.0.0.2")
        assertThat(transport.connections).hasSize(2)
        old.listener.onFailure(old.socket, IOException("obsolete"), null)
        old.listener.onClosed(old.socket, 1000, "obsolete")
        old.listener.onOpen(old.socket, mockk<Response>())
        advanceTimeBy(2000)
        runCurrent()
        assertThat(transport.connections).hasSize(2)
        assertThat(client.connectionState.value).isEqualTo(NaviV2ConnectionState.CONTROL_CONNECTING)
        verify(exactly = 0) { transport.connections.last().socket.cancel() }
        client.stop()
    }

    @Test
    fun `stop cancels queued retry until explicitly started again`() = runTest {
        val transport = Transport()
        val client = NaviWebSocketV2Client(this, transport.client)
        client.setTarget("127.0.0.1")
        client.start()
        val first = transport.connections.single()
        first.listener.onFailure(first.socket, IOException("lost"), null)
        client.stop()
        client.setTarget("127.0.0.1")
        advanceTimeBy(60000)
        runCurrent()
        assertThat(transport.connections).hasSize(1)
        assertThat(client.sendJsonData("vehicle", true, null)).isFalse()
        client.start()
        assertThat(transport.connections).hasSize(2)
        client.stop()
    }

    @Test
    fun `ready callback waits for all enabled sockets and repeats after stream recovery`() = runTest {
        val transport = Transport()
        val client = protocolClient(this, transport)
        val readySessions = mutableListOf<String>()
        client.onSessionReady = { readySessions.add(it.sessionId) }
        openSession(client, transport)
        val firstStreams = transport.connections.drop(1)
        firstStreams.dropLast(1).forEach { it.listener.onOpen(it.socket, mockk()) }
        assertThat(client.isReady()).isFalse()
        assertThat(client.isStreamReady(NaviV2Constants.JSON_NAMES.first())).isTrue()
        assertThat(readySessions).isEmpty()
        firstStreams.last().let { it.listener.onOpen(it.socket, mockk()) }
        assertThat(client.isReady()).isTrue()
        assertThat(readySessions).containsExactly("session_1")

        val broken = firstStreams.first()
        broken.listener.onClosed(broken.socket, 1008, "lost")
        assertThat(client.isReady()).isFalse()
        assertThat(client.getSession()).isNull()
        firstStreams.forEach { verify { it.socket.cancel() } }
        broken.listener.onFailure(broken.socket, IOException("duplicate failure"), null)
        val count = transport.connections.size
        advanceTimeBy(2000)
        runCurrent()
        assertThat(transport.connections).hasSize(count + 1)
        openSession(client, transport, start = false, sessionId = "session_2")
        transport.connections.drop(count + 1).forEach { it.listener.onOpen(it.socket, mockk()) }
        assertThat(client.isReady()).isTrue()
        assertThat(readySessions).containsExactly("session_1", "session_2").inOrder()
        client.stop()
    }

    @Test
    fun `replacement session rejects callbacks from old session sockets`() = runTest {
        val transport = Transport()
        val client = protocolClient(this, transport)
        openSession(client, transport)
        val oldStreams = transport.connections.drop(1)
        client.setTarget("127.0.0.2")
        openSession(client, transport, start = false, sessionId = "replacement")
        val newStreams = transport.connections.drop(2 + oldStreams.size)
        oldStreams.forEach {
            it.listener.onOpen(it.socket, mockk())
            it.listener.onClosed(it.socket, 1000, "obsolete")
            it.listener.onFailure(it.socket, IOException("obsolete"), null)
        }
        assertThat(client.getSession()!!.sessionId).isEqualTo("replacement")
        assertThat(client.isReady()).isFalse()
        newStreams.forEach {
            verify(exactly = 0) { it.socket.cancel() }
            it.listener.onOpen(it.socket, mockk())
        }
        assertThat(client.isReady()).isTrue()
        client.stop()
    }

    @Test
    fun `enqueue failure is returned and immediately invalidates session readiness`() = runTest {
        val transport = Transport()
        val client = protocolClient(this, transport)
        openSession(client, transport)
        val streams = transport.connections.drop(1)
        streams.forEach { it.listener.onOpen(it.socket, mockk()) }
        val enqueue = NaviWebSocketV2Client::class.java.getDeclaredMethod(
            "enqueueJson", String::class.java, WebSocket::class.java, String::class.java
        ).apply { isAccessible = true }
        val key = "json:${NaviV2Constants.JSON_NAMES.first()}"
        assertThat(enqueue.invoke(client, key, streams.first().socket, "item")).isEqualTo(true)

        every { streams.first().socket.send(any<String>()) } returns false
        assertThat(enqueue.invoke(client, key, streams.first().socket, "item")).isEqualTo(false)
        assertThat(client.isReady()).isFalse()
        assertThat(client.getSession()).isNull()
        assertThat(client.connectionState.value).isEqualTo(NaviV2ConnectionState.RECONNECTING)
        client.stop()
    }

    @Test
    fun `control closing clears readiness immediately and delayed stream opens are cancelled`() = runTest {
        val transport = Transport()
        val client = protocolClient(this, transport)
        openSession(client, transport)
        val control = transport.connections.first()
        val streams = transport.connections.drop(1)
        streams.dropLast(1).forEach { it.listener.onOpen(it.socket, mockk()) }
        control.listener.onClosing(control.socket, 1000, "disconnect")
        assertThat(client.getSession()).isNull()
        assertThat(client.isStreamReady(NaviV2Constants.JSON_NAMES.first())).isFalse()
        streams.last().let {
            it.listener.onOpen(it.socket, mockk())
            verify(atLeast = 2) { it.socket.cancel() }
        }
        client.stop()
    }

    @Test
    fun `session opening timeout recovers sockets that never open`() = runTest {
        val transport = Transport()
        val client = protocolClient(this, transport)
        openSession(client, transport)
        val count = transport.connections.size
        advanceTimeBy(NaviV2Constants.MAX_RECONNECT_DELAY_MS)
        runCurrent()
        assertThat(client.getSession()).isNull()
        assertThat(client.connectionState.value).isEqualTo(NaviV2ConnectionState.RECONNECTING)
        advanceTimeBy(2000)
        runCurrent()
        assertThat(transport.connections).hasSize(count + 1)
        client.stop()
    }

    private fun protocolClient(scope: CoroutineScope, transport: Transport): NaviWebSocketV2Client =
        NaviWebSocketV2Client(scope, transport.client)

    private fun openSession(
        client: NaviWebSocketV2Client,
        transport: Transport,
        start: Boolean = true,
        sessionId: String = "session_1"
    ) {
        if (start) {
            client.setTarget("127.0.0.1")
            client.start()
        }
        // Install a validated session without Android's unavailable JSON encoder.
        // The actual JSON socket factory, callbacks, readiness and recovery run.
        val type = NaviWebSocketV2Client::class.java
        val slot = type.getDeclaredField("control").apply { isAccessible = true }.get(client)
        slot.javaClass.getDeclaredField("open").apply { isAccessible = true }.set(slot, true)
        val session = NaviWebSocketV2Client.validateManifest(manifest(sessionId = sessionId))
        type.getDeclaredField("currentSession").apply { isAccessible = true }.set(client, session)
        val generation = type.getDeclaredField("generation").apply { isAccessible = true }.getLong(client)
        val openStream = type.declaredMethods.single { it.name == "openJsonStream" }
            .apply { isAccessible = true }
        session.streams.values.filter { it.kind == "json" && it.enabled }.forEach {
            openStream.invoke(client, generation, slot, session, it)
        }
    }

    private class Transport {
        data class Connection(val socket: WebSocket, val listener: WebSocketListener)
        val connections = mutableListOf<Connection>()
        val client = mockk<OkHttpClient> {
            every { newWebSocket(any<Request>(), any<WebSocketListener>()) } answers {
                val socket = mockk<WebSocket>(relaxed = true)
                every { socket.send(any<String>()) } returns true
                connections.add(Connection(socket, secondArg()))
                socket
            }
        }
    }

    private fun assertRejected(manifest: JSONObject) {
        val failure = runCatching { NaviWebSocketV2Client.validateManifest(manifest) }.exceptionOrNull()
        assertThat(failure).isNotNull()
    }

    private fun manifest(
        protocolVersion: Any = 2,
        sessionId: String = "session_1",
        revision: Any = 1,
        entries: List<JSONObject>? = null,
        entryOverrides: Map<String, Any> = emptyMap()
    ): JSONObject = json(mapOf(
        "type" to "subscription_manifest",
        "protocol_version" to protocolVersion,
        "session_id" to sessionId,
        "revision" to revision,
        "streams" to array(entries ?: streamEntries(entryOverrides))
    ))

    private fun streamEntries(overrides: Map<String, Any> = emptyMap()): List<JSONObject> =
        NaviV2Constants.CATALOG.mapIndexed { index, entry ->
            streamEntry(entry, index + 1, if (index == 0) overrides else emptyMap())
        }

    private fun streamEntry(entry: Pair<String, String>, handle: Int, overrides: Map<String, Any> = emptyMap()): JSONObject =
        json(mapOf(
            "kind" to entry.first, "name" to entry.second, "schema_version" to 1,
            "stream_handle" to handle, "enabled" to true,
            "params" to json(mapOf("delivery_mode" to "periodic", "interval_ms" to 600, "stale_timeout_ms" to 2500))
        ) + overrides)

    // Android's local-test JSONObject is a stub. Mock only its data access;
    // validation, lifecycle, socket identity and retry scheduling remain real.
    private fun json(values: Map<String, Any>): JSONObject = mockk {
        every { this@mockk.get(any<String>()) } answers { values.getValue(firstArg()) }
        every { getString(any()) } answers { values.getValue(firstArg()) as String }
        every { getBoolean(any()) } answers { values.getValue(firstArg()) as Boolean }
        every { getJSONObject(any()) } answers { values.getValue(firstArg()) as JSONObject }
        every { getJSONArray(any()) } answers { values.getValue(firstArg()) as JSONArray }
        every { keys() } answers { values.keys.iterator() }
    }

    private fun array(values: List<JSONObject>): JSONArray = mockk {
        every { length() } returns values.size
        every { getJSONObject(any()) } answers { values[firstArg()] }
    }
}
