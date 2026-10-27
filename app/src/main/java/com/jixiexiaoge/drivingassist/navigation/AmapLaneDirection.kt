package com.jixiexiaoge.drivingassist.navigation

internal data class AmapLaneDirection(val glyph: String, val label: String)

internal fun amapBroadcastFlag(value: Any?): Boolean? = when (value?.toString()?.trim()?.lowercase()) {
    "true", "1" -> true
    "false", "0" -> false
    else -> null
}

// Lane Back_icon uses its own numbering, not the navigation maneuver ICON table.
internal fun amapLaneDirection(rawIcon: String): AmapLaneDirection? = when (rawIcon.trim().lowercase()) {
    "0", "15", "straight" -> AmapLaneDirection("↑", "直行")
    "1", "left" -> AmapLaneDirection("←", "左转")
    "2", "straight_left" -> AmapLaneDirection("↑←", "直行/左转")
    "3", "right" -> AmapLaneDirection("→", "右转")
    "4", "straight_right" -> AmapLaneDirection("↑→", "直行/右转")
    "5", "uturn_left" -> AmapLaneDirection("↶", "左掉头")
    "6", "left_right" -> AmapLaneDirection("←→", "左转/右转")
    "7", "straight_left_right" -> AmapLaneDirection("←↑→", "左/直/右")
    "8", "uturn_right" -> AmapLaneDirection("↷", "右掉头")
    "9", "straight_uturn_left" -> AmapLaneDirection("↑↶", "直行/左掉头")
    "10", "straight_uturn_right" -> AmapLaneDirection("↑↷", "直行/右掉头")
    "11", "left_uturn_left" -> AmapLaneDirection("←↶", "左转/左掉头")
    "12", "right_uturn_right" -> AmapLaneDirection("→↷", "右转/右掉头")
    else -> null
}

internal data class AmapJunctionLane(
    val number: Int, val direction: AmapLaneDirection?, val recommended: Boolean,
    val rawIcon: String = "",
)
internal data class AmapJunctionLanes(
    val lanes: List<AmapJunctionLane>,
    val leftToRightKnown: Boolean,
    val actionDistanceM: Int?,
)
