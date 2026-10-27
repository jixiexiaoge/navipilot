package com.jixiexiaoge.drivingassist.camera

import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class WebRtcSignalingTest {
    @Test
    fun `stream request defaults to the road camera`() {
        val body = JSONObject(WebRtcSignaling.requestBody("offer-sdp", "navipilot-test"))

        assertThat(body.getString("sdp")).isEqualTo("offer-sdp")
        // 只订阅长焦 road：广角视野过宽会让车道数/车道宽度偏高，已不再使用
        assertThat(body.getJSONArray("cameras").toList()).containsExactly("road")
        assertThat(body.getJSONArray("bridge_services_in").length()).isEqualTo(0)
        assertThat(body.getJSONArray("bridge_services_out").length()).isEqualTo(0)
        assertThat(body.getString("client_id")).isEqualTo("navipilot-test")
        assertThat(body.getBoolean("takeover")).isFalse()
        assertThat(body.getBoolean("carrot_state")).isFalse()
        assertThat(WebRtcSignaling.streamUrl("10.0.0.12"))
            .isEqualTo("http://10.0.0.12:5001/stream")
    }

    @Test
    fun `camera candidates only contain the road camera`() {
        assertThat(WebRtcSignaling.CAMERA_CANDIDATES)
            .containsExactly(listOf(WebRtcSignaling.CAMERA_ROAD))
            .inOrder()
        val wide = JSONObject(WebRtcSignaling.requestBody("sdp", "id", listOf(WebRtcSignaling.CAMERA_WIDE)))
        assertThat(wide.getJSONArray("cameras").toList()).containsExactly("wideRoad")
    }

    @Test
    fun `answer requires valid sdp and defaults to answer type`() {
        assertThat(WebRtcSignaling.parseAnswer("""{"sdp":"remote-sdp"}"""))
            .isEqualTo(WebRtcStreamAnswer("remote-sdp", "answer"))
        assertThrows(IOException::class.java) {
            WebRtcSignaling.parseAnswer("""{"type":"answer"}""")
        }
        assertThrows(IOException::class.java) {
            WebRtcSignaling.parseAnswer("not-json")
        }
    }

    @Test
    fun `busy response is distinct from transport errors`() {
        val busy = WebRtcSignaling.error(
            409,
            """{"ok":false,"code":"carrot_vision_busy","error":"already active"}""",
        )
        val generic = WebRtcSignaling.error(503, "offline")

        assertThat(busy).isInstanceOf(WebRtcBusyException::class.java)
        assertThat(busy).hasMessageThat().contains("already active")
        assertThat(generic).isNotInstanceOf(WebRtcBusyException::class.java)
        assertThat(generic).hasMessageThat().contains("HTTP 503")
    }

    @Test
    fun `unreachable signaling is separated from ordinary stream failures`() {
        // 5001 端口连不上：车机没开视频流 → 单独相位，界面提示「需开启视频流功能」
        assertThat(isSignalingUnreachable(java.net.ConnectException("Failed to connect to /10.0.0.12:5001")))
            .isTrue()
        assertThat(isSignalingUnreachable(java.net.SocketTimeoutException("timeout"))).isTrue()
        assertThat(isSignalingUnreachable(java.net.NoRouteToHostException("no route"))).isTrue()
        assertThat(isSignalingUnreachable(java.net.UnknownHostException("cp.local"))).isTrue()
        assertThat(isSignalingUnreachable(IOException("Failed to connect to /10.0.0.12:5001"))).isTrue()

        // 端口通但服务/协商有问题 → 仍按普通重连处理，不误报「去开视频流」
        assertThat(isSignalingUnreachable(WebRtcSignaling.error(503, "offline"))).isFalse()
        assertThat(isSignalingUnreachable(IOException("WebRTC ICE FAILED"))).isFalse()
        assertThat(isSignalingUnreachable(IOException("No WebRTC video frame for 20 seconds"))).isFalse()
    }
}
