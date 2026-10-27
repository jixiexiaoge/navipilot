package com.jixiexiaoge.drivingassist

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.slot
import io.mockk.just
import io.mockk.Runs
import kotlinx.coroutines.runBlocking
import fi.iki.elonen.NanoHTTPD
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.URL
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertThrows
import org.json.JSONObject

class NetworkTargetSelectionTest {
    private lateinit var client: CarrotManNetworkClient

    @Before
    fun setUp() {
        mockkStatic(Log::class, MediaPlayer::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.v(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { MediaPlayer.create(any<Context>(), any<Int>()) } returns null
        client = CarrotManNetworkClient(mockk(relaxed = true))
    }

    @After
    fun tearDown() {
        client.cleanup()
        unmockkStatic(Log::class, MediaPlayer::class)
    }

    @Test
    fun `other discovered devices cannot hijack the selected target or its telemetry`() {
        // Given
        var statusPackets = 0
        client.setOnOpenpilotStatusReceived { statusPackets++ }
        val first = CarrotManNetworkClient.DeviceInfo("192.168.88.10", 7706, "test")
        val second = first.copy(ip = "192.168.88.11")
        // When
        discover(first)
        discover(second)
        parse("""{"ip":"192.168.88.11","port":7706,"active":true}""", second.ip)
        // Then
        assertThat(client.getDeviceIP()).isEqualTo(first.ip)
        assertThat(client.getCurrentDevice()!!.ip).isEqualTo(first.ip)
        assertThat(client.getDiscoveredDevices()).hasSize(2)
        assertThat(statusPackets).isEqualTo(0)
        parse("""{"ip":"192.168.88.10","port":7706,"active":true}""", first.ip)
        assertThat(statusPackets).isEqualTo(1)
    }

    @Test
    fun `explicit target switches remain stable and stopping removes the selected IP`() {
        // Given
        val first = CarrotManNetworkClient.DeviceInfo("192.168.88.10", 7706, "test")
        val second = first.copy(ip = "192.168.88.11")
        discover(first)
        discover(second)
        // When
        client.connectToDevice(second)
        discover(first)
        // Then
        assertThat(client.getDeviceIP()).isEqualTo(second.ip)
        assertThat(client.getCurrentDevice()!!.ip).isEqualTo(second.ip)
        discover(second.copy(port = 7000, version = "updated", lastSeen = 1))
        assertThat(client.getCurrentDevice()!!.port).isEqualTo(7000)
        assertThat(client.getCurrentDevice()!!.lastSeen).isGreaterThan(1L)
        client.stop()
        assertThat(client.getDeviceIP()).isNull()
    }

    private fun discover(device: CarrotManNetworkClient.DeviceInfo) {
        client.javaClass.getDeclaredMethod("addDiscoveredDevice", CarrotManNetworkClient.DeviceInfo::class.java)
            .apply { isAccessible = true }.invoke(client, device)
    }

    @Test
    fun `command packets always use UDP 7706 regardless of an advertised HTTP port`() = runBlocking {
        // Given
        val socket = mockk<DatagramSocket>(relaxed = true)
        val packet = slot<DatagramPacket>()
        every { socket.send(capture(packet)) } just Runs
        client.javaClass.getDeclaredField("dataSocket").apply { isAccessible = true }.set(client, socket)
        client.javaClass.getDeclaredField("isRunning").apply { isAccessible = true }.setBoolean(client, true)
        client.connectToDevice(CarrotManNetworkClient.DeviceInfo("127.0.0.1", 7000, "test"))
        // When
        client.sendCarrotManDataImmediately(CarrotManFields(carrotCmd = "LANECHANGE", carrotArg = "LEFT"))
        // Then
        assertThat(packet.captured.port).isEqualTo(7706)
        assertThat(packet.captured.address.hostAddress).isEqualTo("127.0.0.1")
    }

    @Test
    fun `actual heartbeat sender cannot replace a pending lane request or erase its diagnostic`() = runBlocking {
        val packets = mutableListOf<JSONObject>()
        val socket = mockk<DatagramSocket>(relaxed = true)
        every { socket.send(any()) } answers {
            val packet = firstArg<DatagramPacket>()
            packets += JSONObject(String(packet.data, packet.offset, packet.length, Charsets.UTF_8))
        }
        client.javaClass.getDeclaredField("dataSocket").apply { isAccessible = true }.set(client, socket)
        client.javaClass.getDeclaredField("isRunning").apply { isAccessible = true }.setBoolean(client, true)
        client.connectToDevice(CarrotManNetworkClient.DeviceInfo("127.0.0.1", 7000, "test"))
        client.sendCommandPacket(CarrotManFields(carrotCmd = "LANECHANGE", carrotArg = "LEFT"), 101, "127.0.0.1")
        client.sendHeartbeat()

        val heartbeat = packets.last()
        assertThat(heartbeat.keys().asSequence().toSet()).containsExactly("carrotIndex", "epochTime", "timezone")
        // carrot_serv updates the command mailbox only when carrotCmd is present.
        val mailbox = packets.last { it.has("carrotCmd") }
        assertThat(mailbox.getString("carrotCmd")).isEqualTo("LANECHANGE")
        assertThat(mailbox.getString("carrotArg")).isEqualTo("LEFT")
        client.sendCommandPacket(CarrotManFields(), 102, "127.0.0.1")
        client.sendHeartbeat()
        assertThat(client.lastCommandJson()!!.getString("carrotCmd")).isEqualTo("LANECHANGE")

        client.connectToDevice(CarrotManNetworkClient.DeviceInfo("127.0.0.2", 7000, "test"))
        assertThat(client.lastCommandJson()).isNull()
        val count = packets.size
        assertThrows(java.io.IOException::class.java) {
            runBlocking {
                client.sendCommandPacket(
                    CarrotManFields(carrotCmd = "LANECHANGE", carrotArg = "RIGHT"), 103, "127.0.0.1",
                )
            }
        }
        assertThat(packets).hasSize(count)
    }

    @Test
    fun `selected target listeners replay discovery and clear when the target stops`() {
        // Given
        val manager = NetworkManager(mockk(relaxed = true), mutableStateOf(CarrotManFields()))
        manager.javaClass.getDeclaredField("carrotNetworkClient").apply { isAccessible = true }.set(manager, client)
        val cameraTargets = mutableListOf<String>()
        val lateTargets = mutableListOf<String>()
        manager.setOnDeviceIPUpdated { cameraTargets += it }
        fun publishTarget() {
            manager.javaClass.getDeclaredMethod("notifySelectedDeviceIP").apply { isAccessible = true }.invoke(manager)
        }
        try {
            // When
            val target = CarrotManNetworkClient.DeviceInfo("192.168.88.10", 7000, "test")
            discover(target)
            publishTarget()
            manager.setOnDeviceIPUpdated { lateTargets += it }
            discover(target.copy(ip = "192.168.88.11"))
            publishTarget()
            // Then
            assertThat(cameraTargets).containsExactly(target.ip)
            assertThat(lateTargets).containsExactly(target.ip)
            client.stop()
            publishTarget()
            assertThat(cameraTargets).containsExactly(target.ip, "").inOrder()
            assertThat(lateTargets).containsExactly(target.ip, "").inOrder()
        } finally {
            manager.cleanup()
        }
    }

    @Test
    fun `JSON device IP selects the connection target even when broadcast sender differs`() {
        var statusPackets = 0
        client.setOnOpenpilotStatusReceived { statusPackets++ }
        parse("""{"ip":"10.0.0.149","navi_debug":0}""", "192.168.88.247")
        parse("""{"ip":"10.0.0.149","active":true}""", "192.168.88.247")

        assertThat(client.getDeviceIP()).isEqualTo("10.0.0.149")
        assertThat(client.getDiscoveredDevices().map { it.ip }).containsExactly("10.0.0.149")
        assertThat(statusPackets).isEqualTo(1)
        parse("""{"ip":"10.0.0.150","active":true}""", "10.0.0.149")
        assertThat(statusPackets).isEqualTo(1)
        assertThat(client.getDeviceIP()).isEqualTo("10.0.0.149")
    }

    @Test
    fun `invalid advertised device addresses fall back to the UDP sender`() {
        listOf("", "not-a-host", "10.0.0.999", "127.0.0.1", "0.0.0.0", "224.0.0.1").forEach { invalid ->
            parse("""{"ip":"$invalid","navi_debug":0}""", "10.0.0.149")
        }
        assertThat(client.getDiscoveredDevices().map { it.ip }).containsExactly("10.0.0.149")
        assertThat(client.getDeviceIP()).isEqualTo("10.0.0.149")
    }

    @Test
    fun `a newly discovered LAN device replaces an expired target without a manual connection`() {
        val old = CarrotManNetworkClient.DeviceInfo("192.168.88.247", 7706, "active-scan", lastSeen = 1L)
        client.connectToDevice(old)

        parse("""{"ip":"10.0.0.149","navi_debug":0}""", "10.0.0.149")

        assertThat(client.getDeviceIP()).isEqualTo("10.0.0.149")
    }

    @Test
    fun `active discovery follows the current LAN without probing the previous subnet or own address`() {
        val method = client.javaClass.getDeclaredMethod("discoveryHosts", List::class.java)
            .apply { isAccessible = true }
        val current = method.invoke(client, listOf("10.0.0.196")) as List<*>
        assertThat(current).contains("10.0.0.149")
        assertThat(current).doesNotContain("10.0.0.196")
        assertThat(current).doesNotContain("192.168.88.247")
        assertThat(current.all { it is String && it.startsWith("10.0.0.") }).isTrue()
        assertThat(method.invoke(client, emptyList<String>()) as List<*>).isEmpty()
    }

    @Test
    fun `open HTTP ports are not devices without the bounded Carrot heartbeat response`() {
        var body = """{"ok":true,"hb":null}"""
        var status = NanoHTTPD.Response.Status.OK
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response =
                newFixedLengthResponse(status, "application/json", body)
        }
        server.start()
        val method = client.javaClass.getDeclaredMethod("probeCarrotServer", URL::class.java)
            .apply { isAccessible = true }
        val url = URL("http://127.0.0.1:${server.listeningPort}/api/heartbeat_status")
        try {
            assertThat(method.invoke(client, url)).isEqualTo(true)
            body = """{"ok":true}"""
            assertThat(method.invoke(client, url)).isEqualTo(false)
            body = "<html>Unrelated server</html>"
            assertThat(method.invoke(client, url)).isEqualTo(false)
            body = """{"ok":true,"hb":null,"padding":"${"x".repeat(1024)}"}"""
            assertThat(method.invoke(client, url)).isEqualTo(false)
            body = """{"ok":true,"hb":null}"""
            status = NanoHTTPD.Response.Status.NOT_FOUND
            assertThat(method.invoke(client, url)).isEqualTo(false)
        } finally {
            server.stop()
        }
    }

    private fun parse(json: String, ip: String) {
        client.javaClass.getDeclaredMethod("parseDeviceBroadcast", String::class.java, String::class.java)
            .apply { isAccessible = true }.invoke(client, json, ip)
    }
}
