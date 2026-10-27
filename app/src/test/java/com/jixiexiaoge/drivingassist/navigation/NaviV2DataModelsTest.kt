package com.jixiexiaoge.drivingassist.navigation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NaviV2DataModelsTest {
    @Test
    fun `segment time preserves explicit zero and omits missing or invalid values`() {
        // Given / When / Then
        assertThat(GuidanceData(timeSec = 0).toJson().getInt("time_sec")).isEqualTo(0)
        assertThat(GuidanceData(timeSec = 15).toJson().getInt("time_sec")).isEqualTo(15)
        for (value in listOf(null, -1, 604801)) {
            assertThat(GuidanceData(timeSec = value).toJson().has("time_sec")).isFalse()
        }
    }

    @Test
    fun `envelope keeps source time distinct from transmission time`() {
        // Given / When
        val json = JsonEnvelope(sessionId = "test", name = "guidance_current",
            manifestRevision = 1, streamHandle = 1, sequence = 1,
            sourceTimestampMs = 1000, sentAtMs = 2500, present = true, value = GuidanceData().toJson()).toJson()
        // Then
        assertThat(json.getLong("source_timestamp_ms")).isEqualTo(1000)
        assertThat(json.getLong("sent_at_ms")).isEqualTo(2500)
    }
    @Test
    fun `traffic signal emits only active phase countdown`() {
        val red = TrafficSignalData(
            visible = true,
            redValid = true,
            redOn = true,
            redRemainSec = 12,
            greenRemainSec = 0,
            leftRemainSec = 0
        ).toJson()

        assertThat(red.getJSONObject("lights").getJSONObject("red").getInt("remain_sec"))
            .isEqualTo(12)
        assertThat(red.getJSONObject("ui_counter").getInt("remain_sec")).isEqualTo(12)
        assertThat(red.getJSONObject("lights").has("green")).isFalse()
        assertThat(red.getJSONObject("lights").has("right")).isFalse()
        assertThat(red.getJSONObject("lights").has("uturn")).isFalse()
    }

    @Test
    fun `catalog is exactly the supported twenty eight streams`() {
        assertThat(NaviV2Constants.CATALOG).hasSize(28)
        assertThat(NaviV2Constants.JSON_NAMES).hasSize(13)
        assertThat(NaviV2Constants.JSON_NAMES).doesNotContain("noa_intent")
    }

    @Test
    fun `no event speed omits sdi section and invalid road limit`() {
        val empty = SpeedData(roadLimitKph = 55).toJson()
        assertThat(empty.has("sdi")).isFalse()
        assertThat(empty.has("section")).isFalse()
        assertThat(empty.has("road_limit_kph")).isFalse()
        assertThat(SpeedData(roadLimitKph = 60, primarySdi = AmapSdiSource(0, 100, 60, 1, 1000).toJson())
            .toJson().getJSONObject("sdi").getInt("type"))
            .isEqualTo(0)
    }

    @Test
    fun `unknown route totals and signal distance are not fabricated`() {
        assertThat(RouteData(remainDistanceM = 500).toJson().has("total_distance_m")).isFalse()
        assertThat(TrafficSignalData().toJson().has("distance_m")).isFalse()
    }

    @Test
    fun `traffic signal serializes left turn phase`() {
        val signal = TrafficSignalData(
            visible = true,
            leftValid = true,
            leftOn = true,
            leftRemainSec = 8
        ).toJson()

        assertThat(signal.getJSONObject("lights").getJSONObject("left").getBoolean("valid"))
            .isTrue()
        assertThat(signal.getJSONObject("lights").getJSONObject("left").getInt("remain_sec"))
            .isEqualTo(8)
        assertThat(signal.getJSONObject("ui_counter").getInt("remain_sec")).isEqualTo(8)
    }

    @Test
    fun `NOA position reports map match independently from coordinate availability`() {
        val gpsOnly = NoaIntentData(lat = 31.2, lon = 121.5, mapMatched = false).toJson()
        val mapMatched = NoaIntentData(lat = 31.2, lon = 121.5, mapMatched = true).toJson()

        assertThat(gpsOnly.getJSONObject("position").getBoolean("map_matched")).isFalse()
        assertThat(mapMatched.getJSONObject("position").getBoolean("map_matched")).isTrue()
    }

    @Test
    fun `unknown lane metadata and signal counter are omitted rather than manufactured`() {
        val lane = LaneData(laneCount = 1, lanes = listOf(LaneItem(isRecommended = true))).toJson()
        for (key in listOf("road_category", "distance_m", "turn_info", "available")) {
            assertThat(lane.has(key)).isFalse()
        }
        assertThat(lane.getJSONArray("lanes").getJSONObject(0).has("is_available")).isFalse()
        assertThat(TrafficSignalData().toJson().has("ui_counter")).isFalse()
        // 车端 7711 只给灯色、不给倒计时：亮灯必须发出来，但不能编造 remain_sec
        val zeroPhase = TrafficSignalData(redValid = true, redOn = true).toJson()
        val red = zeroPhase.getJSONObject("lights").getJSONObject("red")
        assertThat(red.getBoolean("valid")).isTrue()
        assertThat(red.getBoolean("on")).isTrue()
        assertThat(red.has("remain_sec")).isFalse()
        assertThat(zeroPhase.has("ui_counter")).isFalse()
        // 灭灯且无倒计时：仍然整键省略，不把「未知」写成 on=false
        assertThat(TrafficSignalData(redValid = true, redOn = false).toJson()
            .getJSONObject("lights").has("red")).isFalse()
    }

    @Test
    fun `right and uturn schema do not add a masking generic green phase`() {
        for ((name, data) in listOf(
            "right" to TrafficSignalData(rightValid = true, rightOn = true, rightRemainSec = 9),
            "uturn" to TrafficSignalData(uturnValid = true, uturnOn = true, uturnRemainSec = 6),
        )) {
            val json = data.toJson()
            assertThat(json.getJSONObject("lights").length()).isEqualTo(1)
            assertThat(json.getJSONObject("lights").getJSONObject(name).getBoolean("on")).isTrue()
            assertThat(json.getJSONObject("ui_counter").getInt("remain_sec")).isGreaterThan(0)
        }
    }

    @Test
    fun `unverified signal encodings absent timing zero and expired phase cannot become valid`() {
        for (source in listOf(
            AmapNavigationSignalSource(3, 1, 8, 1, 1000),
            AmapNavigationSignalSource(4, 1, 8, 1, 1000),
            AmapNavigationSignalSource(2, 3, 8, 1, 1000),
            AmapNavigationSignalSource(2, 2, 8, 1, 1000),
            AmapNavigationSignalSource(1, 1, 0, 1, 1000),
            AmapNavigationSignalSource(1, 1, 1000, 1, 1000),
        )) assertThat(source.toSignal(1000)).isNull()
        val valid = AmapNavigationSignalSource(1, 1, 8, 1, 1000)
        assertThat(valid.toSignal(999)).isNull()
        assertThat(valid.toSignal(9000)).isNull()
        assertThat(valid.toSignal(1000)!!.toJson().getJSONObject("lights").has("red")).isTrue()
    }
}
