package com.jixiexiaoge.drivingassist.navigation

object LeftGuidancePolicy {
    const val PREFERENCE_KEY = "suppress_highway_left_guidance"
    const val WITHDRAWAL_REASON = "left_guidance_filtered"
    private const val REGULAR_ROAD_CATEGORY = 6
    private val forkLeftCodes = setOf(102, 105, 112, 115, 7, 44, 17, 75, 76, 118)

    // An expired/missing category must not turn a blocked highway action back on.
    fun suppress(turnCode: Int, category: AmapRoadCategorySource?, enabled: Boolean): Boolean =
        enabled && turnCode in forkLeftCodes && category?.carrotCategory() != REGULAR_ROAD_CATEGORY
}
