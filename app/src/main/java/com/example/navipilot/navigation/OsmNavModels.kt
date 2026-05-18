package com.example.navipilot.navigation

/**
 * 简单化的 OSM 导航存根
 * 导航功能待后续实现
 */
class OsmNavDest(val name: String, val lon: Double, val lat: Double)

/**
 * 路线信息
 */
data class RouteInfo(
    val distanceM: Int,
    val durationS: Int,
    val geometryJson: String
)

/**
 * 车道信息 - 使用 CarrotManDataModels 中的 LaneInfo
 */
typealias LaneInfo = com.example.navipilot.LaneInfo