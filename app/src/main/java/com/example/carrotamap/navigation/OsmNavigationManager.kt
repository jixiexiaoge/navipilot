package com.example.carrotamap.navigation

import com.example.carrotamap.CarrotManNetworkClient

/**
 * 车道导航存根
 */
object LaneLevelNavigator {
    enum class LaneChangeDirection { NONE, LEFT, RIGHT, MULTIPLE_LEFT, MULTIPLE_RIGHT }
    enum class LaneChangeUrgency { LOW, MEDIUM, HIGH, CRITICAL }

    data class LaneGuidance(
        val needLaneChange: Boolean = false,
        val laneChangeUrgency: LaneChangeUrgency = LaneChangeUrgency.LOW,
        val laneChangeDirection: LaneChangeDirection = LaneChangeDirection.NONE,
        val message: String = "",
        val currentLane: Int = 0,
        val confidence: Double = 1.0
    )
}

/**
 * 路线引擎存根
 */
class RouteEngine {
    var onRouteCalculated: ((ParsedRoute) -> Unit)? = null
    var onLaneGuidanceUpdated: ((LaneLevelNavigator.LaneGuidance) -> Unit)? = null
    var parsedRoute: ParsedRoute? = null
}

/**
 * 解析后的路线数据
 */
data class ParsedRoute(
    val distance: Double = 0.0,
    val duration: Double = 0.0,
    val geometry: List<GeoCoordinate> = emptyList(),
    val provider: String = ""
)

/**
 * OSM 导航管理器存根
 */
class OsmNavigationManager(
    private val context: android.content.Context,
    private val networkClient: com.example.carrotamap.CarrotManNetworkClient?,
    private val carrotManFields: androidx.compose.runtime.MutableState<com.example.carrotamap.CarrotManFields>?,
    private val activeNavMode: androidx.compose.runtime.MutableState<String>?
) {
    val routeEngine = RouteEngine()

    fun startOsmNavigation(dest: OsmNavDest, routeInfo: RouteInfo) {}
    fun stopOsmNavigation() {}
    fun updateLocation(location: android.location.Location) {}
    fun getCurrentInstruction(): NavigationInstruction? = null
    fun getCurrentLaneInfo(): List<com.example.carrotamap.navigation.LaneInfo>? = null
    fun cleanup() {}
}

data class NavigationInstruction(
    val distanceToManeuver: Double? = null,
    val maneuverType: String? = null,
    val maneuverModifier: String? = null,
    val nextRoadName: String? = null,
    val speedLimit: Double? = null,
    val distanceRemaining: Double? = null,
    val timeRemaining: Double? = null
)