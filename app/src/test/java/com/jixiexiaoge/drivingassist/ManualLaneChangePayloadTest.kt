package com.jixiexiaoge.drivingassist

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ManualLaneChangePayloadTest {
    @Test
    fun `left lane button payload retains its direction and packet index`() {
        val payload = CarrotManNetworkClient.build7706Payload(
            CarrotManFields(carrotCmd = "LANECHANGE", carrotArg = "LEFT"),
            packetCarrotIndex = 101,
            includeCommand = true,
        )

        assertThat(payload.getString("carrotCmd")).isEqualTo("LANECHANGE")
        assertThat(payload.getString("carrotArg")).isEqualTo("LEFT")
        assertThat(payload.getLong("carrotIndex")).isEqualTo(101)
        assertThat(payload.getLong("carrotCmdIndex")).isEqualTo(101)
    }

    @Test
    fun `right lane button payload retains its direction and packet index`() {
        val payload = CarrotManNetworkClient.build7706Payload(
            CarrotManFields(carrotCmd = "LANECHANGE", carrotArg = "RIGHT"),
            packetCarrotIndex = 102,
            includeCommand = true,
        )

        assertThat(payload.getString("carrotCmd")).isEqualTo("LANECHANGE")
        assertThat(payload.getString("carrotArg")).isEqualTo("RIGHT")
        assertThat(payload.getLong("carrotIndex")).isEqualTo(102)
        assertThat(payload.getLong("carrotCmdIndex")).isEqualTo(102)
    }
}
