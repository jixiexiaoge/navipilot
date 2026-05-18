package com.example.navipilot.navigation

import kotlin.math.*

/**
 * 地理坐标点
 * 移植自 Dashy helpers.py Coordinate
 */
data class GeoCoordinate(
    val latitude: Double,
    val longitude: Double
) {
    companion object {
        const val EARTH_MEAN_RADIUS = 6371007.2 // 米
    }

    /**
     * Haversine 距离计算（米）
     */
    fun distanceTo(other: GeoCoordinate): Double {
        val dlat = Math.toRadians(other.latitude - latitude)
        val dlon = Math.toRadians(other.longitude - longitude)
        val haversineDlat = sin(dlat / 2.0).let { it * it }
        val haversineDlon = sin(dlon / 2.0).let { it * it }
        val y = haversineDlat +
                cos(Math.toRadians(latitude)) *
                cos(Math.toRadians(other.latitude)) *
                haversineDlon
        val x = 2 * asin(sqrt(y))
        return x * EARTH_MEAN_RADIUS
    }

    /**
     * 方位角计算（0-360°）
     */
    fun bearingTo(other: GeoCoordinate): Double {
        val lat1 = Math.toRadians(latitude)
        val lat2 = Math.toRadians(other.latitude)
        val dlon = Math.toRadians(other.longitude - longitude)
        val x = sin(dlon) * cos(lat2)
        val y = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dlon)
        val bearing = Math.toDegrees(atan2(x, y))
        return (bearing + 360) % 360
    }

    operator fun minus(other: GeoCoordinate) =
        GeoCoordinate(latitude - other.latitude, longitude - other.longitude)

    operator fun plus(other: GeoCoordinate) =
        GeoCoordinate(latitude + other.latitude, longitude + other.longitude)

    operator fun times(c: Double) =
        GeoCoordinate(latitude * c, longitude * c)

    fun dot(other: GeoCoordinate): Double =
        latitude * other.latitude + longitude * other.longitude
}

/**
 * 点到线段的最短距离
 * 移植自 Dashy helpers.py minimum_distance
 */
fun minimumDistance(a: GeoCoordinate, b: GeoCoordinate, p: GeoCoordinate): Double {
    if (a.distanceTo(b) < 0.01) return a.distanceTo(p)
    val ap = p - a
    val ab = b - a
    val t = (ap.dot(ab) / ab.dot(ab)).coerceIn(0.0, 1.0)
    val projection = a + ab * t
    return projection.distanceTo(p)
}


/**
 * 点到线段的投影
 * 返回 Triple(投影点, 参数t(0-1), 距离)
 * 移植自 Dashy helpers.py project_onto_segment
 */
fun projectOntoSegment(
    a: GeoCoordinate, b: GeoCoordinate, p: GeoCoordinate
): Triple<GeoCoordinate, Double, Double> {
    val segLen = a.distanceTo(b)
    if (segLen < 0.01) return Triple(a, 0.0, a.distanceTo(p))
    val ap = p - a
    val ab = b - a
    val t = (ap.dot(ab) / ab.dot(ab)).coerceIn(0.0, 1.0)
    val projection = a + ab * t
    return Triple(projection, t, projection.distanceTo(p))
}

/**
 * 沿路线几何的距离计算（使用投影距离，精度更高）
 * 修复：使用 projectOntoSegment 计算沿线段的精确投影距离，
 * 而非 geometry[i].distanceTo(pos)（偏离路线时误差大）
 * 移植自 Dashy helpers.py distance_along_geometry
 */
fun distanceAlongGeometry(geometry: List<GeoCoordinate>, pos: GeoCoordinate): Double {
    if (geometry.isEmpty()) return 0.0
    if (geometry.size == 1) return geometry[0].distanceTo(pos)
    if (geometry.size == 2) {
        val (_, t, _) = projectOntoSegment(geometry[0], geometry[1], pos)
        return geometry[0].distanceTo(geometry[1]) * t
    }

    var cumulativeDistance = 0.0
    var bestProjectedDistance = 0.0
    var closestDistance = Double.MAX_VALUE

    for (i in 0 until geometry.size - 1) {
        val a = geometry[i]
        val b = geometry[i + 1]
        val segLen = a.distanceTo(b)
        if (segLen < 0.01) {
            cumulativeDistance += segLen
            continue
        }

        val (_, t, dist) = projectOntoSegment(a, b, pos)
        if (dist < closestDistance) {
            closestDistance = dist
            // 沿路线的精确距离 = 之前所有线段的累计长度 + 当前线段上的投影距离
            bestProjectedDistance = cumulativeDistance + segLen * t
        }
        cumulativeDistance += segLen
    }
    return bestProjectedDistance
}

/**
 * 角度归一化到 -180 ~ 180
 * 移植自 Dashy helpers.py normalize_angle
 */
fun normalizeAngle(angle: Double): Double {
    var a = angle
    while (a > 180) a -= 360
    while (a < -180) a += 360
    return a
}

/**
 * 计算两段路线之间的转向角度
 * 正值=左转，负值=右转
 * 移植自 Dashy helpers.py calculate_turn_angle
 */
fun calculateTurnAngle(
    currentGeometry: List<GeoCoordinate>,
    nextGeometry: List<GeoCoordinate>,
    samples: Int = 3
): Double {
    if (currentGeometry.size < 2 || nextGeometry.size < 2) return 0.0

    // 当前路段末尾的方位角
    val endIdx = currentGeometry.size - 1
    val startSample = (endIdx - samples).coerceAtLeast(0)
    val bearingBefore = currentGeometry[startSample].bearingTo(currentGeometry[endIdx])

    // 下一路段开头的方位角
    val endSample = samples.coerceAtMost(nextGeometry.size - 1)
    val bearingAfter = nextGeometry[0].bearingTo(nextGeometry[endSample])

    return normalizeAngle(bearingBefore - bearingAfter)
}

/**
 * 在路线坐标中找到最近的点
 * 返回 Pair(索引, 距离)
 * 移植自 Dashy helpers.py find_closest_point_on_route
 */
fun findClosestPointOnRoute(
    pos: GeoCoordinate, routeCoords: List<GeoCoordinate>
): Pair<Int, Double> {
    var minDist = Double.MAX_VALUE
    var minIdx = 0
    for (i in routeCoords.indices) {
        val d = pos.distanceTo(routeCoords[i])
        if (d < minDist) {
            minDist = d
            minIdx = i
        }
    }
    return Pair(minIdx, minDist)
}

/**
 * 计算从某个索引开始的剩余距离
 * 移植自 Dashy helpers.py calculate_remaining_distance
 */
fun calculateRemainingDistance(
    routeCoords: List<GeoCoordinate>, fromIdx: Int
): Double {
    var dist = 0.0
    for (i in fromIdx until routeCoords.size - 1) {
        dist += routeCoords[i].distanceTo(routeCoords[i + 1])
    }
    return dist
}

/**
 * 在指定索引处计算转向角度
 * 移植自 Dashy helpers.py compute_turn_angle_at_index
 */
fun computeTurnAngleAtIndex(
    routeCoords: List<GeoCoordinate>,
    turnIdx: Int,
    lookBehind: Double = 50.0,
    lookAhead: Double = 50.0
): Double {
    if (turnIdx <= 0 || turnIdx >= routeCoords.size - 1) return 0.0

    // 向后找 lookBehind 米的点
    var behindIdx = turnIdx
    var behindDist = 0.0
    while (behindIdx > 0 && behindDist < lookBehind) {
        behindDist += routeCoords[behindIdx].distanceTo(routeCoords[behindIdx - 1])
        behindIdx--
    }

    // 向前找 lookAhead 米的点
    var aheadIdx = turnIdx
    var aheadDist = 0.0
    while (aheadIdx < routeCoords.size - 1 && aheadDist < lookAhead) {
        aheadDist += routeCoords[aheadIdx].distanceTo(routeCoords[aheadIdx + 1])
        aheadIdx++
    }

    val bearingBefore = routeCoords[behindIdx].bearingTo(routeCoords[turnIdx])
    val bearingAfter = routeCoords[turnIdx].bearingTo(routeCoords[aheadIdx])

    return normalizeAngle(bearingBefore - bearingAfter)
}
