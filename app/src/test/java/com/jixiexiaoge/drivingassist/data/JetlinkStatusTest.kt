package com.jixiexiaoge.drivingassist.data

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class JetlinkStatusTest {
    private fun json(
        state: String = "ready", active: Boolean = false, fresh: Boolean = true,
        severity: String = "ok", label: String = "MAC",
    ) = """{"active":true,"available":true,"jetlink":{"label":"$label","state":"$state",
        "active":$active,"fresh":$fresh,"severity":"$severity","reason":"test"}}"""

    @Test
    fun `ready and active are distinct and outer eGPU flags do not activate Jetlink`() {
        assertThat(parseJetlinkStatus(json()).phase).isEqualTo(JetlinkPhase.Ready)
        assertThat(parseJetlinkStatus(json()).tone).isEqualTo(JetlinkTone.Ready)
        assertThat(parseJetlinkStatus(json(active = true)).phase).isEqualTo(JetlinkPhase.Active)
        assertThat(parseJetlinkStatus(json(active = true)).tone).isEqualTo(JetlinkTone.Active)
        assertThat(parseJetlinkStatus("""{"active":true,"available":true,"jetlink":null}""").phase)
            .isEqualTo(JetlinkPhase.Waiting)
    }

    @Test
    fun `remembered identity and stale health never become green connection evidence`() {
        val disconnected = parseJetlinkStatus(json("disconnected", active = true, fresh = false, label = "iOS"))
        assertThat(disconnected.label).isEqualTo("iOS")
        assertThat(disconnected.phase).isEqualTo(JetlinkPhase.Disconnected)
        assertThat(disconnected.tone).isEqualTo(JetlinkTone.Error)
        assertThat(parseJetlinkStatus(json(fresh = false, label = "jetSON")).tone).isEqualTo(JetlinkTone.Warning)
        assertThat(parseJetlinkStatus(json("waiting", fresh = false, severity = "unknown")).tone)
            .isEqualTo(JetlinkTone.Neutral)
    }

    @Test
    fun `health faults override active green and unknown states remain neutral`() {
        assertThat(parseJetlinkStatus(json(active = true, severity = "error")).tone).isEqualTo(JetlinkTone.Error)
        assertThat(parseJetlinkStatus(json(active = true, severity = "warning")).tone).isEqualTo(JetlinkTone.Warning)
        val unknown = parseJetlinkStatus(json("future-state", active = true, label = "arbitrary"))
        assertThat(unknown.label).isEqualTo("Jetlink")
        assertThat(unknown.phase).isEqualTo(JetlinkPhase.Unknown)
        assertThat(unknown.tone).isEqualTo(JetlinkTone.Neutral)
        assertThat(parseJetlinkStatus(json("loading")).tone).isEqualTo(JetlinkTone.Pending)
    }

    @Test
    fun `compact HUD labels retain phase and distinguish health from absence`() {
        assertThat(parseJetlinkStatus(json()).badgeText).isEqualTo("MAC READY")
        assertThat(parseJetlinkStatus(json(active = true)).badgeText).isEqualTo("MAC")
        assertThat(parseJetlinkStatus(json(active = true, severity = "warning")).badgeText)
            .isEqualTo("MAC WARN")
        assertThat(parseJetlinkStatus(json(active = true, severity = "error")).badgeText)
            .isEqualTo("MAC ERROR")
        assertThat(parseJetlinkStatus(json(fresh = false, label = "jetSON")).badgeText)
            .isEqualTo("jetSON READY DATA?")
        assertThat(parseJetlinkStatus(json("loading")).badgeText).isEqualTo("MAC LOAD")
        assertThat(parseJetlinkStatus(json("retrying")).badgeText).isEqualTo("MAC RETRY")
        assertThat(parseJetlinkStatus(json("disconnected", fresh = false)).badgeText).isEqualTo("MAC OFF")
    }

    @Test
    fun `active Mac without Jetson health telemetry matches Carrot green label`() {
        val mac = parseJetlinkStatus(json(active = true, fresh = false, severity = "unknown"))
        assertThat(mac.phase).isEqualTo(JetlinkPhase.Active)
        assertThat(mac.tone).isEqualTo(JetlinkTone.Active)
        assertThat(mac.badgeText).isEqualTo("MAC")
        assertThat(mac.healthUnavailable).isFalse()
        val jetson = parseJetlinkStatus(json(active = true, fresh = false, severity = "unknown", label = "jetSON"))
        assertThat(jetson.tone).isEqualTo(JetlinkTone.Warning)
        assertThat(jetson.badgeText).isEqualTo("jetSON DATA?")
        assertThat(parseJetlinkStatus(json("disconnected", true, false, "unknown")).tone)
            .isEqualTo(JetlinkTone.Error)
    }

    @Test
    fun `missing malformed or incorrectly typed observations fail explicitly`() {
        for (value in listOf("{}", """{"jetlink":{}}""", json().replace("\"fresh\":true", "\"fresh\":\"true\""))) {
            assertThrows(Exception::class.java) { parseJetlinkStatus(value) }
        }
    }

    @Test
    fun `client only reads selected host status endpoint and rejects HTTP and oversized payloads`() {
        var body = json()
        var code = 200
        val client = JetlinkStatusClient(OkHttpClient.Builder().addInterceptor {
            assertThat(it.request().method).isEqualTo("GET")
            assertThat(it.request().url.toString()).isEqualTo("http://127.0.0.1:7000/api/egpu/model")
            Response.Builder().request(it.request()).protocol(Protocol.HTTP_1_1).code(code).message("test")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build())
        assertThat(runBlocking { client.read("127.0.0.1") }.phase).isEqualTo(JetlinkPhase.Ready)
        code = 404
        assertThrows(IOException::class.java) { runBlocking { client.read("127.0.0.1") } }
        code = 200
        body = " ".repeat(65_537)
        assertThrows(IOException::class.java) { runBlocking { client.read("127.0.0.1") } }
    }
}
