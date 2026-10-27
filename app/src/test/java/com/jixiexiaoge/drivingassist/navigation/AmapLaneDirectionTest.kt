package com.jixiexiaoge.drivingassist.navigation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AmapLaneDirectionTest {
    @Test
    fun `lane zero means straight and is not a maneuver icon`() {
        // Given / When / Then
        assertThat(amapLaneDirection("0")).isEqualTo(AmapLaneDirection("↑", "直行"))
        assertThat(amapLaneDirection("15")).isEqualTo(amapLaneDirection("0"))
        assertThat(amapLaneDirection("1")!!.label).isEqualTo("左转")
        assertThat(amapLaneDirection("3")!!.label).isEqualTo("右转")
    }

    @Test
    fun `verified combined directions retain all permitted turns`() {
        // Given / When / Then
        val labels = mapOf(2 to "直行/左转", 4 to "直行/右转", 5 to "左掉头", 6 to "左转/右转",
            7 to "左/直/右", 8 to "右掉头", 9 to "直行/左掉头", 10 to "直行/右掉头",
            11 to "左转/左掉头", 12 to "右转/右掉头")
        labels.forEach { (code, label) -> assertThat(amapLaneDirection("$code")!!.label).isEqualTo(label) }
    }

    @Test
    fun `missing and unverified complex icons remain unknown`() {
        // Given / When / Then
        listOf("", "13", "14", "16", "999", "null", "-1").forEach {
            assertThat(amapLaneDirection(it)).isNull()
        }
    }

    @Test
    fun `broadcast flags support actual boolean integer and string encodings without guessing`() {
        // Given / When / Then
        listOf(true, 1, "1", "TRUE", " true ").forEach { assertThat(amapBroadcastFlag(it)).isTrue() }
        listOf(false, 0, "0", "false").forEach { assertThat(amapBroadcastFlag(it)).isFalse() }
        listOf(null, "", "yes", 2).forEach { assertThat(amapBroadcastFlag(it)).isNull() }
    }
}
