package com.jixiexiaoge.drivingassist.navigation

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader

data class AmapCruiseLamp(
    val status: Int,
    val direction: Int,
    val remainingSec: Int,
)

data class AmapCruiseSnapshot(
    val lamps: List<AmapCruiseLamp>,
    val receivedAtMs: Long,
) {
    fun isFresh(nowMs: Long): Boolean = nowMs - receivedAtMs in 0..TTL_MS

    companion object {
        const val TTL_MS = 10_000L
    }
}

/** Cruise codes are independent: status 0/1 = red/green, dir 1/2/3 = left/straight/right. */
internal fun parseAmapCruiseLamps(json: String): List<AmapCruiseLamp> {
    require(json.length <= 16_384 && json.toByteArray(Charsets.UTF_8).size <= 16_384) {
        "Cruise lights exceed 16 KiB"
    }
    JsonReader(StringReader(json)).use { reader ->
        reader.isLenient = false
        reader.beginArray()
        val lamps = mutableListOf<AmapCruiseLamp>()
        while (reader.hasNext()) {
            require(lamps.size < 16) { "Cruise lights exceed 16 entries" }
            reader.beginObject()
            var status: Int? = null
            var direction: Int? = null
            var countdown: Int? = null
            val keys = mutableSetOf<String>()
            while (reader.hasNext()) {
                val key = reader.nextName()
                require(keys.add(key)) { "Duplicate cruise light field: $key" }
                when (key) {
                    "status" -> status = reader.cruiseInt()
                    "dir" -> direction = reader.cruiseInt()
                    "countdown" -> countdown = reader.cruiseInt()
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            require(status != null && direction != null && countdown != null) {
                "Cruise light requires status, dir and countdown"
            }
            require(countdown in 0..3600) { "Invalid cruise countdown" }
            lamps += AmapCruiseLamp(status, direction, countdown)
        }
        reader.endArray()
        require(reader.peek() == JsonToken.END_DOCUMENT) { "Trailing cruise data" }
        return lamps
    }
}

private fun JsonReader.cruiseInt(): Int {
    require(peek() == JsonToken.NUMBER) { "Cruise light field must be an integer number" }
    val text = nextString()
    require(text.matches(Regex("-?(0|[1-9][0-9]*)"))) { "Invalid cruise integer" }
    return requireNotNull(text.toIntOrNull()) { "Cruise integer out of range" }
}
