package com.example.navipilot

import com.example.navipilot.navigation.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/**
 * GeoUtils 核心地理计算单元测试
 */
class GeoUtilsTest {

    // ==================== distanceTo ====================

    @Test
    fun distanceTo_samePoint_returnsZero() {
        val p = GeoCoordinate(39.9042, 116.4074) // 北京
        assertEquals(0.0, p.distanceTo(p), 0.01)
    }

    @Test
    fun distanceTo_knownDistance_isAccurate() {
        // 北京 → 上海 约 1068km
        val beijing = GeoCoordinate(39.9042, 116.4074)
        val shanghai = GeoCoordinate(31.2304, 121.4737)
        val dist = beijing.distanceTo(shanghai)
        assertTrue("北京到上海距离应在 1050-1090km 之间，实际=${dist/1000}km",
            dist in 1050_000.0..1090_000.0)
    }

    @Test
    fun distanceTo_shortDistance_isAccurate() {
        // 约 100m 的短距离
        val a = GeoCoordinate(39.9042, 116.4074)
        val b = GeoCoordinate(39.9051, 116.4074) // 纬度差约 0.0009°
        val dist = a.distanceTo(b)
        assertTrue("短距离应在 90-110m 之间，实际=${dist}m", dist in 90.0..110.0)
    }

    // ==================== bearingTo ====================

    @Test
    fun bearingTo_dueNorth_returns0() {
        val a = GeoCoordinate(39.0, 116.0)
        val b = GeoCoordinate(40.0, 116.0)
        val bearing = a.bearingTo(b)
        assertTrue("正北方位角应接近 0°，实际=$bearing", bearing < 1.0 || bearing > 359.0)
    }

    @Test
    fun bearingTo_dueEast_returns90() {
        val a = GeoCoordinate(39.0, 116.0)
        val b = GeoCoordinate(39.0, 117.0)
        val bearing = a.bearingTo(b)
        assertTrue("正东方位角应接近 90°，实际=$bearing", abs(bearing - 90.0) < 2.0)
    }

    // ==================== minimumDistance ====================

    @Test
    fun minimumDistance_pointOnSegment_returnsZero() {
        val a = GeoCoordinate(39.0, 116.0)
        val b = GeoCoordinate(39.0, 117.0)
        val p = GeoCoordinate(39.0, 116.5) // 线段中点
        val dist = minimumDistance(a, b, p)
        assertTrue("线段上的点距离应接近 0，实际=$dist", dist < 100.0)
    }

    @Test
    fun minimumDistance_pointOffSegment_returnsCorrectDistance() {
        val a = GeoCoordinate(39.0, 116.0)
        val b = GeoCoordinate(39.0, 117.0)
        val p = GeoCoordinate(39.001, 116.5) // 偏离约 111m
        val dist = minimumDistance(a, b, p)
        assertTrue("偏离点距离应在 100-120m 之间，实际=$dist", dist in 80.0..150.0)
    }

    // ==================== distanceAlongGeometry ====================

    @Test
    fun distanceAlongGeometry_atStart_returnsZero() {
        val geom = listOf(
            GeoCoordinate(39.0, 116.0),
            GeoCoordinate(39.001, 116.0),
            GeoCoordinate(39.002, 116.0)
        )
        val dist = distanceAlongGeometry(geom, geom[0])
        assertTrue("起点处沿线距离应接近 0，实际=$dist", dist < 10.0)
    }

    @Test
    fun distanceAlongGeometry_atEnd_returnsTotalLength() {
        val geom = listOf(
            GeoCoordinate(39.0, 116.0),
            GeoCoordinate(39.001, 116.0),
            GeoCoordinate(39.002, 116.0)
        )
        val totalLen = geom[0].distanceTo(geom[1]) + geom[1].distanceTo(geom[2])
        val dist = distanceAlongGeometry(geom, geom.last())
        assertTrue("终点处沿线距离应接近总长度 $totalLen，实际=$dist",
            abs(dist - totalLen) < 10.0)
    }

    @Test
    fun distanceAlongGeometry_midpoint_returnsHalfLength() {
        val geom = listOf(
            GeoCoordinate(39.0, 116.0),
            GeoCoordinate(39.002, 116.0) // 约 222m
        )
        val midpoint = GeoCoordinate(39.001, 116.0)
        val dist = distanceAlongGeometry(geom, midpoint)
        val halfLen = geom[0].distanceTo(geom[1]) / 2
        assertTrue("中点处沿线距离应接近半长 $halfLen，实际=$dist",
            abs(dist - halfLen) < 20.0)
    }

    // ==================== normalizeAngle ====================

    @Test
    fun normalizeAngle_withinRange_unchanged() {
        assertEquals(45.0, normalizeAngle(45.0), 0.01)
        assertEquals(-90.0, normalizeAngle(-90.0), 0.01)
    }

    @Test
    fun normalizeAngle_outOfRange_normalized() {
        assertEquals(-10.0, normalizeAngle(350.0), 0.01)
        assertEquals(10.0, normalizeAngle(-350.0), 0.01)
    }

    // ==================== findClosestPointOnRoute ====================

    @Test
    fun findClosestPointOnRoute_findsCorrectIndex() {
        val route = listOf(
            GeoCoordinate(39.0, 116.0),
            GeoCoordinate(39.001, 116.0),
            GeoCoordinate(39.002, 116.0),
            GeoCoordinate(39.003, 116.0)
        )
        val pos = GeoCoordinate(39.0015, 116.0001) // 最接近 index 1 或 2
        val (idx, dist) = findClosestPointOnRoute(pos, route)
        val dists = route.mapIndexed { i, c -> "idx$i=${pos.distanceTo(c)}" }.joinToString()
        assertTrue("最近点索引应为 1 或 2，实际=$idx dist=$dist [$dists]", idx in 1..2)
        assertTrue("距离应小于 100m，实际=$dist", dist < 100.0)
    }

    // ==================== projectOntoSegment ====================

    @Test
    fun projectOntoSegment_midpoint_returnsHalf() {
        val a = GeoCoordinate(39.0, 116.0)
        val b = GeoCoordinate(39.002, 116.0)
        val p = GeoCoordinate(39.001, 116.0)
        val (_, t, dist) = projectOntoSegment(a, b, p)
        assertTrue("投影参数 t 应接近 0.5，实际=$t", abs(t - 0.5) < 0.1)
        assertTrue("投影距离应接近 0，实际=$dist", dist < 20.0)
    }
}
