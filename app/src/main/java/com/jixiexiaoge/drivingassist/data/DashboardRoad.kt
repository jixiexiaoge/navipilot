@file:Suppress("MagicNumber")

package com.jixiexiaoge.drivingassist.data

internal data class DashboardRoadPoint(val forwardM: Float, val rightM: Float)
internal data class DashboardRoadCurve(val label: String, val points: List<DashboardRoadPoint>, val probability: Float?)

/** Read-only scene inputs; absent model geometry never becomes a default road. */
internal data class DashboardRoad(
    val curves: List<DashboardRoadCurve> = emptyList(),
    /** 红绿灯停止线距离（米），null = 不在停止线范围 */
    val stopLineM: Float? = null,
    /** 7711 CAN 灯色：1 红 / 2 绿 / 3 黄；不是导航灯组 */
    val trafficLightState: Int = 0,
    val leftLineCode: Int? = null,
    val rightLineCode: Int? = null,
)

@Suppress("ReturnCount")
internal fun DashboardRoadCurve.window(startM: Float = 0f, endM: Float = 50f): DashboardRoadCurve? {
    if (points.size < 2) return null
    val start = maxOf(startM, points.first().forwardM)
    val end = minOf(endM, points.last().forwardM)
    if (start >= end) return null
    fun at(distance: Float): DashboardRoadPoint {
        val bIndex = points.indexOfFirst { it.forwardM >= distance }.coerceAtLeast(1)
        val a = points[bIndex - 1]
        val b = points[bIndex]
        val fraction = (distance - a.forwardM) / (b.forwardM - a.forwardM)
        return DashboardRoadPoint(distance, a.rightM + fraction * (b.rightM - a.rightM))
    }
    return copy(points = listOf(at(start)) + points.filter { it.forwardM > start && it.forwardM < end } + at(end))
}

@Suppress("ReturnCount")
internal fun DashboardRoad.laneBand(): Pair<DashboardRoadCurve, DashboardRoadCurve>? {
    val left = curves.firstOrNull { it.label == "左内边界" && (it.probability ?: 0f) >= 0.7f } ?: return null
    val right = curves.firstOrNull { it.label == "右内边界" && (it.probability ?: 0f) >= 0.7f } ?: return null
    if (left.points.size < 2 || right.points.size < 2) return null
    val start = maxOf(left.points.first().forwardM, right.points.first().forwardM)
    val end = minOf(left.points.last().forwardM, right.points.last().forwardM, 50f)
    val a = left.window(start, end) ?: return null
    val b = right.window(start, end) ?: return null
    val knots = (a.points + b.points).map { it.forwardM }.distinct().sorted()
    fun lateral(curve: DashboardRoadCurve, x: Float): Float {
        val upper = curve.points.indexOfFirst { it.forwardM >= x }.coerceAtLeast(1)
        val p = curve.points[upper - 1]
        val q = curve.points[upper]
        return p.rightM + (x - p.forwardM) / (q.forwardM - p.forwardM) * (q.rightM - p.rightM)
    }
    if (knots.any { lateral(a, it) >= lateral(b, it) }) return null
    return a to b
}

private fun roadPoints(value: Any?): List<DashboardRoadPoint> {
    val raw = value as? Map<*, *>
    val x = (raw?.get("x") as? List<*>)?.takeIf { it.size in 2..64 }
    val y = raw?.get("y") as? List<*>
    val points = if (x != null && y != null && x.size == y.size) {
        x.indices.mapNotNull { index ->
            val forward = (x[index] as? Number)?.toFloat()?.takeIf { it in 0f..655.35f }
            val right = (y[index] as? Number)?.toFloat()?.takeIf { it in -32.768f..32.767f }
            if (forward != null && right != null) {
                DashboardRoadPoint(forward, right)
            } else null
        }.takeIf { it.size == x.size }.orEmpty()
    } else emptyList()
    return points.takeIf { it.zipWithNext().all { (a, b) -> a.forwardM < b.forwardM } }.orEmpty()
}

/** Model-space plot only: no calibration, camera projection, lane identity or radar fusion. */
internal fun CompactTelemetry?.dashboardRoad(): DashboardRoad {
    val sample = this?.fresh("modelV2")?.takeIf { observedAtMs >= it.receivedAtMs }
    val model = sample?.frame?.values.orEmpty()
    val curves = buildList {
        fun curve(value: Any?, label: String, probability: Any? = null) {
            val points = roadPoints(value)
            val p = (probability as? Number)?.toFloat()?.takeIf { it.isFinite() && it in 0f..1f }
            if (points.isNotEmpty()) add(DashboardRoadCurve(label, points, p))
        }
        curve(model["position"], "模型预测路径")
        val probabilities = model["laneLineProbs"] as? List<*>
        (model["laneLines"] as? List<*>)?.takeIf { it.size == 4 }?.forEachIndexed { index, value ->
            curve(value, listOf("左外侧线", "左内边界", "右内边界", "右外侧线")[index],
                probabilities?.getOrNull(index))
        }
        (model["roadEdges"] as? List<*>)?.takeIf { it.size == 2 }?.forEachIndexed { index, value ->
            curve(value, if (index == 0) "模型左路缘" else "模型右路缘")
        }
    }
    return DashboardRoad(
        curves = curves,
        leftLineCode = this?.fresh("carState")?.frame?.values?.get("leftLaneLine").lineCode(),
        rightLineCode = this?.fresh("carState")?.frame?.values?.get("rightLaneLine").lineCode(),
    )
}

private fun Any?.lineCode(): Int? = (this as? Number)?.toInt()?.takeIf {
    it in setOf(0, 1, 10, 11, 20, 21, 30, 31)
}
