package com.jixiexiaoge.drivingassist.navigation

import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Assert.assertThrows
import org.junit.Test

class AmapTrafficSegmentsTest {
    @Test
    fun `actual TMC schema preserves distances order status and route ownership`() {
        // Given / When
        val result = parseAmapTrafficSegments(payload(), 7, 1000)
        // Then
        assertThat(result).isEqualTo(AmapTrafficSegments(1000, 250,
            listOf(AmapTrafficSegment(1, 600), AmapTrafficSegment(3, 400)), 7, 1000))
    }

    @Test
    fun `missing progress is not invented as zero and unrecognized status is retained`() {
        // Given
        val raw = JSONObject(payload()).apply {
            remove("finish_distance")
            getJSONArray("tmc_info").getJSONObject(0).put("tmc_status", 99)
        }
        // When
        val result = parseAmapTrafficSegments(raw.toString(), 1, 1000)
        // Then
        assertThat(result.finishedM).isNull()
        assertThat(result.segments.first().status).isEqualTo(99)
    }

    @Test
    fun `invalid totals progress lengths fractions and excessive segments are rejected`() {
        // Given
        val badPayloads = listOf(
            JSONObject(payload()).put("total_distance", 0),
            JSONObject(payload()).put("total_distance", 1200),
            JSONObject(payload()).put("finish_distance", -1),
            JSONObject(payload()).put("finish_distance", 1001),
            JSONObject(payload()).put("finish_distance", 2.5),
            JSONObject(payload()).apply { getJSONArray("tmc_info").getJSONObject(0).put("tmc_segment_distance", -1) },
            JSONObject(payload()).put("tmc_info", org.json.JSONArray()),
            JSONObject(payload()).put("tmc_info", org.json.JSONArray(List(513) { JSONObject("""{"tmc_status":1,"tmc_segment_distance":1}""") })),
        )
        // When / Then
        badPayloads.forEach { raw ->
            assertThrows(IllegalArgumentException::class.java) { parseAmapTrafficSegments(raw.toString(), 1, 1000) }
        }
    }

    @Test
    fun `zero and completed reported progress are both valid`() {
        // Given / When / Then
        listOf(0, 1000).forEach { finished ->
            assertThat(parseAmapTrafficSegments(JSONObject(payload()).put("finish_distance", finished).toString(), 1, 1000)
                .finishedM).isEqualTo(finished)
        }
    }

    @Test
    fun `bounded payload rejects oversized JSON before parsing`() {
        // Given / When / Then
        assertThrows(IllegalArgumentException::class.java) { parseAmapTrafficSegments(" ".repeat(65537), 1, 1000) }
    }

    private fun payload() = """{"total_distance":1000,"finish_distance":250,"tmc_info":[
        {"tmc_status":1,"tmc_segment_distance":600},{"tmc_status":3,"tmc_segment_distance":400}]}"""
}
