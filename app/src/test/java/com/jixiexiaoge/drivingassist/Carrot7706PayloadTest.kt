package com.jixiexiaoge.drivingassist

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class Carrot7706PayloadTest {
    @Test
    fun `heartbeat only carries the communication trio`() {
        val payload = CarrotManNetworkClient.build7706Payload(
            CarrotManFields(carrotCmd = "LANECHANGE", carrotArg = "LEFT", nRoadLimitSpeed = 100),
            packetCarrotIndex = 1,
            currentTimeMs = 2000,
        )

        assertThat(payload.keys().asSequence().toSet())
            .containsExactly("carrotIndex", "epochTime", "timezone")
        assertThat(payload.getLong("epochTime")).isEqualTo(2)
    }

    @Test
    fun `command packet carries only command mailbox plus base trio`() {
        val payload = CarrotManNetworkClient.build7706Payload(
            fields = CarrotManFields(
                carrotCmd = "SPEED",
                carrotArg = "60",
                isNavigating = true,
                goalPosX = 121.5,
                goalPosY = 31.2,
                nRoadLimitSpeed = 100,
                nTBTTurnType = 4,
                nTBTDist = 320,
            ),
            packetCarrotIndex = 2,
            includeCommand = true,
        )

        assertThat(payload.keys().asSequence().toSet())
            .containsExactly("carrotIndex", "epochTime", "timezone", "carrotCmdIndex", "carrotCmd", "carrotArg")
        assertThat(payload.getLong("carrotCmdIndex")).isEqualTo(2)
        assertThat(payload.getString("carrotCmd")).isEqualTo("SPEED")
        assertThat(payload.getString("carrotArg")).isEqualTo("60")
        assertThat(payload.has("goalPosX")).isFalse()
        assertThat(payload.has("nRoadLimitSpeed")).isFalse()
        assertThat(payload.has("nTBTDist")).isFalse()
    }

    @Test
    fun `destination packet carries only destination compatibility block plus base trio`() {
        val payload = CarrotManNetworkClient.build7706Payload(
            fields = CarrotManFields(
                isNavigating = true,
                goalPosX = 121.5,
                goalPosY = 31.2,
                szGoalName = "目的地",
                nRoadLimitSpeed = 100,
            ),
            packetCarrotIndex = 3,
            includeDestination = true,
        )

        assertThat(payload.keys().asSequence().toSet())
            .containsExactly("carrotIndex", "epochTime", "timezone", "goalPosX", "goalPosY", "szGoalName")
        assertThat(payload.getDouble("goalPosX")).isEqualTo(121.5)
        assertThat(payload.getDouble("goalPosY")).isEqualTo(31.2)
        assertThat(payload.getString("szGoalName")).isEqualTo("目的地")
        assertThat(payload.has("nRoadLimitSpeed")).isFalse()
        assertThat(payload.has("carrotCmd")).isFalse()
    }

    @Test
    fun `destination block is omitted when coordinates are invalid`() {
        val payload = CarrotManNetworkClient.build7706Payload(
            fields = CarrotManFields(goalPosX = 0.0, goalPosY = 0.0, szGoalName = "目的地"),
            packetCarrotIndex = 4,
            includeDestination = true,
        )

        assertThat(payload.keys().asSequence().toSet())
            .containsExactly("carrotIndex", "epochTime", "timezone")
    }

    @Test
    fun `copies of one operation keep the same sequence and command mailbox`() {
        val fields = CarrotManFields(carrotCmd = "LANECHANGE", carrotArg = "LEFT")
        val first = CarrotManNetworkClient.build7706Payload(fields, 101, includeCommand = true)
        val second = CarrotManNetworkClient.build7706Payload(fields, 101, includeCommand = true)

        assertThat(second.toString()).isEqualTo(first.toString())
        assertThat(second.getLong("carrotIndex")).isEqualTo(101)
        assertThat(second.getLong("carrotCmdIndex")).isEqualTo(101)
    }

    @Test
    fun `clearing packet carries an empty command with a new sequence`() {
        val payload = CarrotManNetworkClient.build7706Payload(
            fields = CarrotManFields(),
            packetCarrotIndex = 102,
            includeCommand = true,
        )

        assertThat(payload.getString("carrotCmd")).isEmpty()
        assertThat(payload.getString("carrotArg")).isEmpty()
        assertThat(payload.getLong("carrotIndex")).isEqualTo(102)
        assertThat(payload.getLong("carrotCmdIndex")).isEqualTo(102)
    }
}
