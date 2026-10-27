package com.jixiexiaoge.drivingassist.data

import kotlin.math.abs
import kotlin.math.roundToInt

internal enum class LaneGeometryStatus {
    Missing,
    Expired,
    Invalid,
    Maneuvering,

    /**
     * 只能给出**本车道宽度**（以及可信的相邻车道宽度），拿不到宽度带/路缘空间。
     *
     * 触发条件：`lateralPlan` 缺失或不在 `off`、`roadEdges`/`roadEdgeStds` 缺失或超门限、
     * 路缘几何与内线不自洽。这些量只影响「左起第几带」这类扩展信息，
     * 不应该连「本车道多宽」一起撤掉 —— 否则车端没发 lateralPlan 时永远显示 `— | — | — m`。
     */
    WidthOnly,

    Stabilizing,
    Consistent,
}

/**
 * 相邻车道宽度的合理区间（米）。
 * 低于 2.0 更像压线误判，高于 5.0 通常是路肩/匝道口，都不当车道宽度显示。
 */
private const val MIN_OUTER_LANE_M = 2.0f
private const val MAX_OUTER_LANE_M = 5.0f

/** 本车道宽度的合理区间（米），超出即认为两条内线不是同一车道 */
private const val MIN_EGO_LANE_M = 2.5f
private const val MAX_EGO_LANE_M = 4.5f

/** 变道过程中的三个状态：此期间任何宽度都不给，避免把「跨界」当车道宽 */
private val LANE_CHANGE_STATES = setOf("preLaneChange", "laneChangeStarting", "laneChangeFinishing")

/** Geometric width-band estimate only; shoulders and restricted lanes are not classified. */
internal data class LaneGeometry(
    val status: LaneGeometryStatus,
    val widthM: Float? = null,
    val leftSpaceM: Float? = null,
    val rightSpaceM: Float? = null,
    val leftWidthBands: Int? = null,
    val rightWidthBands: Int? = null,
    val provisionalIndex: Int? = null,
    /**
     * 左侧相邻车道宽度（前视 5 米处，左外侧线与自车左线的 y 差）。
     * 仅当左外侧线概率/标准差达标时给值 —— 外侧线基本都是模型外推，不达标就没有参考意义。
     */
    val leftLaneWidthM: Float? = null,
    /** 右侧相邻车道宽度，门限同 [leftLaneWidthM]（用右外侧线） */
    val rightLaneWidthM: Float? = null,
)

@Suppress("ReturnCount", "LongMethod", "CyclomaticComplexMethod")
internal fun measureLaneGeometry(telemetry: CompactTelemetry?): LaneGeometry {
    val raw = telemetry?.services?.get("modelV2") ?: return LaneGeometry(LaneGeometryStatus.Missing)
    val model = telemetry.fresh("modelV2")?.frame?.values ?: return LaneGeometry(LaneGeometryStatus.Expired)
    if (telemetry.observedAtMs < raw.receivedAtMs) return LaneGeometry(LaneGeometryStatus.Invalid)
    val lateral = telemetry.fresh("lateralPlan")?.frame?.values
    if (lateral?.get("laneChangeState") in LANE_CHANGE_STATES) {
        return LaneGeometry(LaneGeometryStatus.Maneuvering)
    }
    // 「稳定车道」只决定能不能给**宽度带**；lateralPlan 缺失不算几何失效 ——
    // 车端某些版本不播 lateralPlan，旧逻辑会因此把宽度整条撤掉（实机上就是 `— | — | — m`）。
    val steady = lateral?.get("laneChangeState") == "off"

    fun floats(value: Any?): List<Float>? = (value as? List<*>)?.map {
        (it as? Number)?.toFloat()?.takeIf(Float::isFinite) ?: return null
    }
    val probabilities = floats(model["laneLineProbs"])
    val lineStds = floats(model["laneLineStds"])
    val edgeStds = (model["roadEdgeStds"] as? List<*>)?.takeIf { it.size == 2 }
    // 模型帧龄：>2 帧说明模型输出没在跟新（20 Hz 下约 100 ms），宽度同样不可信。
    val age = (model["frameAge"] as? Number)?.toDouble()
    if (age == null || !age.isFinite() || age !in 0.0..2.0) {
        return LaneGeometry(LaneGeometryStatus.Invalid)
    }
    // 内线置信度是「本车道宽度」的唯一前提：概率不够或抖动大就等于没有可用的两条边界。
    if (probabilities?.size != 4 || lineStds?.size != 4 ||
        probabilities.any { it !in 0f..1f } ||
        probabilities[1] < 0.7f || probabilities[2] < 0.7f ||
        lineStds[1] !in 0f..0.3f || lineStds[2] !in 0f..0.3f) {
        return LaneGeometry(LaneGeometryStatus.Invalid)
    }
    val lines = model["laneLines"] as? List<*> ?: return LaneGeometry(LaneGeometryStatus.Invalid)
    if (lines.size != 4) return LaneGeometry(LaneGeometryStatus.Invalid)
    val edges = (model["roadEdges"] as? List<*>)?.takeIf { it.size == 2 }
    fun at(curve: Any?, xM: Float): Float? {
        val values = curve as? Map<*, *> ?: return null
        val x = floats(values["x"]) ?: return null
        val y = floats(values["y"]) ?: return null
        if (x.size < 2 || x.size != y.size || x.zipWithNext().any { (a, b) -> b <= a } ||
            xM < x.first() || xM > x.last()) return null
        val i = x.indexOfFirst { it >= xM }
        if (i == 0) return y[0]
        return y[i - 1] + (y[i] - y[i - 1]) * (xM - x[i - 1]) / (x[i] - x[i - 1])
    }

    /**
     * 两条**内线**围出的本车道宽度。Model y 右正，所以左内必须为负、右内必须为正；
     * 越界就当作没有，不用 abs() 把交叉的线藏起来。
     */
    fun egoWidthAt(x: Float): Pair<Float, Float>? {
        val left = at(lines[1], x) ?: return null
        val right = at(lines[2], x) ?: return null
        if (!(left < 0f && right > 0f)) return null
        return (left to right).takeIf { right - left in MIN_EGO_LANE_M..MAX_EGO_LANE_M }
    }

    /** 相邻车道宽度：只差一条**外侧线**，外侧线置信度不达标就留空，不拿猜的线当宽度 */
    fun neighborWidth(innerBoundary: Float, outerIndex: Int, outerOk: Boolean): Float? =
        if (!outerOk) null else at(lines[outerIndex], 5f)?.let { abs(it - innerBoundary) }
            ?.takeIf { it in MIN_OUTER_LANE_M..MAX_OUTER_LANE_M }

    val leftOuterOk = probabilities[0] >= 0.7f && lineStds[0] in 0f..0.3f
    val rightOuterOk = probabilities[3] >= 0.7f && lineStds[3] in 0f..0.3f

    val nearPair = egoWidthAt(5f)
    val farPair = egoWidthAt(15f)
    val pair = nearPair ?: farPair ?: return LaneGeometry(LaneGeometryStatus.Invalid)
    val width = pair.second - pair.first
    val leftLaneW = nearPair?.let { neighborWidth(it.first, 0, leftOuterOk) }
    val rightLaneW = nearPair?.let { neighborWidth(it.second, 3, rightOuterOk) }
    fun widthOnly() = LaneGeometry(
        LaneGeometryStatus.WidthOnly, width,
        leftLaneWidthM = leftLaneW, rightLaneWidthM = rightLaneW,
    )

    // Each edge must independently pass confidence, ordering and two-section checks.
    if (!steady || edges == null) return widthOnly()

    fun fullSection(x: Float): LaneGeometry? {
        val near = egoWidthAt(x) ?: return null
        val sectionWidth = near.second - near.first
        fun space(index: Int): Float? {
            val std = (edgeStds?.get(index) as? Number)?.toFloat()
            if (std == null || !std.isFinite() || std !in 0f..0.3f) return null
            val edge = at(edges[index], x) ?: return null
            return (if (index == 0) near.first - edge else edge - near.second).takeIf { it >= 0f }
        }
        val dl = space(0)
        val dr = space(1)
        if (dl == null && dr == null) return null
        fun bands(distance: Float?): Int? {
            if (distance == null) return null
            val ratio = distance / sectionWidth
            val rounded = ratio.roundToInt()
            return rounded.takeIf { it in 0..5 && abs(ratio - rounded) <= 0.2f }
        }
        val nl = bands(dl)
        val nr = bands(dr)
        // If an extra width band is inferred, require the corresponding outer lane line.
        fun outerValid(index: Int, boundary: Float) =
            probabilities[index] >= 0.7f && lineStds[index] in 0f..0.3f &&
                at(lines[index], x)?.let {
                    val gap = if (index == 0) boundary - it else it - boundary
                    abs(gap - sectionWidth) <= sectionWidth * 0.2f
                } == true
        val left = nl?.takeIf { it == 0 || outerValid(0, near.first) }
        val right = nr?.takeIf { it == 0 || outerValid(3, near.second) }
        return LaneGeometry(
            LaneGeometryStatus.Stabilizing, sectionWidth, dl, dr,
            left, right,
            if (left != null && right != null) left + 1 else null,
            leftLaneWidthM = leftLaneW, rightLaneWidthM = rightLaneW,
        )
    }

    val near = fullSection(5f)
    val far = fullSection(15f)
    val chosen = near ?: far ?: return widthOnly()
    if (near != null && far != null) {
        val widthAgrees = abs(near.widthM!! - far.widthM!!) <= 0.4f
        val left = near.leftWidthBands?.takeIf { widthAgrees && it == far.leftWidthBands }
        val right = near.rightWidthBands?.takeIf { widthAgrees && it == far.rightWidthBands }
        return near.copy(leftWidthBands = left, rightWidthBands = right,
            provisionalIndex = if (left != null && right != null) left + 1 else null)
    }
    // 近远不一致：保留测量值，但撤掉「第几带」这种需要连续确认的结论。
    return chosen.copy(leftWidthBands = null, rightWidthBands = null, provisionalIndex = null)
}

internal class LaneGeometryTracker {
    private var lastReceipt: Long? = null
    private var lastFrame: Long? = null
    private var firstReceipt = 0L
    private var key: Pair<Int?, Int?>? = null
    private var count = 0
    private var latest = LaneGeometry(LaneGeometryStatus.Missing)

    fun update(telemetry: CompactTelemetry?): LaneGeometry {
        val measured = measureLaneGeometry(telemetry)
        val sample = telemetry?.fresh("modelV2")
        val frame = (sample?.frame?.values?.get("frameId") as? Number)?.toLong()
        if (measured.status != LaneGeometryStatus.Stabilizing || measured.provisionalIndex == null ||
            frame == null || frame < 0) {
            lastReceipt = null
            lastFrame = null
            key = null
            count = 0
            latest = measured.copy(provisionalIndex = null)
            return latest
        }
        if (sample.receivedAtMs == lastReceipt) return latest
        val nextKey = measured.leftWidthBands to measured.rightWidthBands
        val uninterrupted = lastReceipt?.let { sample.receivedAtMs - it in 1..250 } == true &&
            lastFrame?.let { frame > it } == true && key == nextKey
        if (!uninterrupted) {
            count = 0
            firstReceipt = sample.receivedAtMs
        }
        count = (count + 1).coerceAtMost(3)
        key = nextKey
        lastReceipt = sample.receivedAtMs
        lastFrame = frame
        latest = if (count >= 3 && sample.receivedAtMs - firstReceipt >= 300) {
            measured.copy(status = LaneGeometryStatus.Consistent)
        } else measured.copy(provisionalIndex = null)
        return latest
    }
}
