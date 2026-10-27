package com.jixiexiaoge.drivingassist.navigation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TrafficSignalComposerTest {

    private fun amapRed(remain: Int) = TrafficSignalData(
        visible = true, redValid = true, redOn = true, redRemainSec = remain)

    private fun amapLeftGreen(remain: Int) = TrafficSignalData(
        visible = true, leftValid = true, leftOn = true, leftRemainSec = remain)

    private fun car(color: Int, distance: Float?) =
        SignalWorld(stopLineDistM = distance, lightColor = color, ageMs = 100)

    @Test
    fun `car red wins without borrowing an unrelated red countdown or stop line distance`() {
        val merged = TrafficSignalComposer.compose(amapRed(8), car(1, 42.4f))!!
        assertThat(merged.visible).isTrue()
        assertThat(merged.distanceM).isNull()
        assertThat(merged.redOn).isTrue()
        assertThat(merged.redRemainSec).isEqualTo(0)
        assertThat(merged.leftOn).isFalse()
    }

    @Test
    fun `car red keeps phase when amap only has left turn green`() {
        // 相位冲突：车端说红，高德说左转绿 → 不借用左转的倒计时
        val merged = TrafficSignalComposer.compose(amapLeftGreen(15), car(1, 60f))!!
        assertThat(merged.redOn).isTrue()
        assertThat(merged.redRemainSec).isEqualTo(0)
        assertThat(merged.leftOn).isFalse()
        val json = merged.toJson()
        assertThat(json.getJSONObject("lights").getJSONObject("red").getBoolean("on")).isTrue()
        assertThat(json.getJSONObject("lights").getJSONObject("red").has("remain_sec")).isFalse()
        assertThat(json.has("ui_counter")).isFalse()
    }

    @Test
    fun `car green does not borrow amap left direction or countdown`() {
        val merged = TrafficSignalComposer.compose(amapLeftGreen(12), car(2, 30f))!!
        assertThat(merged.leftOn).isFalse()
        assertThat(merged.leftRemainSec).isEqualTo(0)
        assertThat(merged.greenOn).isTrue()
        assertThat(merged.distanceM).isNull()
    }

    @Test
    fun `car green without amap emits a lamp but no countdown`() {
        val merged = TrafficSignalComposer.compose(null, car(2, 18.6f))!!
        assertThat(merged.greenOn).isTrue()
        assertThat(merged.distanceM).isNull()
        val json = merged.toJson()
        assertThat(json.getJSONObject("lights").getJSONObject("green").getBoolean("valid")).isTrue()
        assertThat(json.has("ui_counter")).isFalse()
    }

    @Test
    fun `yellow has no 7714 slot and never gets faked as red or green`() {
        assertThat(TrafficSignalComposer.compose(amapRed(5), car(3, 40f))).isNull()
        assertThat(TrafficSignalComposer.compose(null, car(3, 40f))).isNull()
    }

    @Test
    fun `distance outside 125m or sentinel 127 is rejected`() {
        assertThat(TrafficSignalComposer.compose(null, car(1, 125.0f))).isNotNull()
        assertThat(TrafficSignalComposer.compose(null, car(1, 125.1f))).isNull()
        assertThat(TrafficSignalComposer.compose(null, car(1, 127f))).isNull()
        assertThat(TrafficSignalComposer.compose(null, car(1, 0f))).isNull()
        assertThat(TrafficSignalComposer.compose(null, car(1, null))).isNull()
    }

    @Test
    fun `no car frame falls back to amap only`() {
        val amap = amapRed(9)
        assertThat(TrafficSignalComposer.compose(amap, null)).isSameInstanceAs(amap)
        assertThat(TrafficSignalComposer.compose(null, null)).isNull()
    }

    @Test
    fun `chosen source reflects which side actually drove the phase`() {
        assertThat(TrafficSignalComposer.chosenSource(amapRed(8), car(1, 40f))).isEqualTo("car")
        assertThat(TrafficSignalComposer.chosenSource(null, car(1, 140f))).isEqualTo("car_out_of_range")
        assertThat(TrafficSignalComposer.chosenSource(amapRed(8), car(3, 40f)))
            .isEqualTo("car_yellow_unsupported")
        assertThat(TrafficSignalComposer.chosenSource(null, null)).isEqualTo("none")
    }

    @Test
    fun `off lamp without countdown still omits the key`() {
        // 原有保证：灭灯且无倒计时不写灯对象，避免把「未知」写成 on=false
        val json = TrafficSignalData(visible = true, redValid = true, redOn = false).toJson()
        assertThat(json.getJSONObject("lights").has("red")).isFalse()
    }

    @Test
    fun `expired or future car observation cannot override fresh amap`() {
        val amap = amapRed(9)
        for (age in listOf(-1L, 1501L, Long.MAX_VALUE)) {
            assertThat(TrafficSignalComposer.compose(amap, car(2, 40f).copy(ageMs = age)))
                .isSameInstanceAs(amap)
        }
    }
}
