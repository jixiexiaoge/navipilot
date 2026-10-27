package com.jixiexiaoge.drivingassist.navigation

import org.json.JSONObject

data class AmapTrafficSegment(val status: Int, val distanceM: Int)
data class AmapTrafficSegments(
    val totalM: Int,
    val finishedM: Int?,
    val segments: List<AmapTrafficSegment>,
    val routeGeneration: Long,
    val receivedAtMs: Long,
)

internal fun parseAmapTrafficSegments(raw: String, generation: Long, nowMs: Long): AmapTrafficSegments {
    require(raw.length <= 65536) { "TMC payload too large" }
    val root = JSONObject(raw)
    fun integer(obj: JSONObject, key: String): Int =
        requireNotNull(obj.get(key).toString().toIntOrNull()) { "Invalid TMC integer: $key" }
    val total = integer(root, "total_distance")
    require(total > 0) { "Invalid TMC total distance" }
    val finished = if (root.has("finish_distance") && !root.isNull("finish_distance")) {
        integer(root, "finish_distance").also { require(it in 0..total) { "Invalid TMC progress" } }
    } else null
    val array = root.getJSONArray("tmc_info")
    require(array.length() in 1..512) { "Invalid TMC segment count" }
    val segments = List(array.length()) { index ->
        val item = array.getJSONObject(index)
        val distance = integer(item, "tmc_segment_distance")
        require(distance > 0) { "Invalid TMC segment distance" }
        AmapTrafficSegment(integer(item, "tmc_status"), distance)
    }
    require(segments.sumOf { it.distanceM.toLong() } == total.toLong()) { "TMC distances do not match total" }
    return AmapTrafficSegments(total, finished, segments, generation, nowMs)
}
