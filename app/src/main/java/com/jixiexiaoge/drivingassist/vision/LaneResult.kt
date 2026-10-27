@file:Suppress("MagicNumber")

package com.jixiexiaoge.drivingassist.vision

/**
 * 一帧的识别结果。
 *
 * 只保留「车道数 / 当前车道」这条业务线——画面里的可行驶区掩码与车道线折线
 * 已按需求移除，不再计算，所以这里也没有对应的像素数据。
 *
 * @property lanesLeft 左侧四分类argmax，旧解码按含本车道计数解释，不是核准的当前车道号。
 * @property lanesRight 右侧分类argmax；第3类是否饱和未知，融合层不发布其编号。
 * @property lanesTotal 同帧候选总数 = left + right - 1；禁止与历史中值拼接编号。
 * @property atMs 该帧进入 App 推理旁路的墙钟时间（毫秒），不是相机采集时间。消费方（例如 7714 的 lane_current）
 *   用它判断模型是否还在出数：模型随实时页生命周期启停，停更后必须让位给地图源，
 *   不能把最后一帧车道数一直当成有效值。
 */
internal class LaneResult(
    val model: String,
    val lanesLeft: Int,
    val lanesRight: Int,
    val lanesTotal: Int,
    val latencyMs: Int,
    val avgLatencyMs: Float,
    val frames: Long,
    val srcW: Int,
    val srcH: Int,
    val meanLuma: Int,
    val atMs: Long = System.currentTimeMillis(),
    val receivedMonoMs: Long = System.nanoTime() / 1_000_000,
    val targetHost: String = "",
    val routeGeneration: Long = -1,
    val sessionId: Long = 0,
    val leftScores: List<Float> = emptyList(),
    val rightScores: List<Float> = emptyList(),
)

/** Opaque four-class scores; class 3 is not proven to mean exactly three or three-plus. */
internal fun decodeLaneClass(buffer: FloatArray, offset: Int): Int {
    if (offset < 0 || offset > buffer.size - 4) return -1
    val classes = (0 until 4).map { buffer[offset + it] }
    val best = classes.indices.maxBy { classes[it] }
    return if (classes.all { it.isFinite() } && classes[best] > 1e-4f &&
        classes.count { it == classes[best] } == 1) best else -1
}

internal fun laneCountTotal(left: Int, right: Int): Int =
    if (left in 1..3 && right in 1..3) left + right - 1 else -1

/** 推理层对外暴露的运行状态（低频更新，仅供 UI 文本使用） */
internal class LaneStatus(
    val libReady: Boolean = false,
    val model: String = "",
    val message: String = "",
    val latencyMs: Int = 0,
    val avgLatencyMs: Float = 0f,
    val fps: Float = 0f,
    val frames: Long = 0,
) {
    val running: Boolean get() = libReady && message.isEmpty()
}

/**
 * 模型选择策略。
 *
 * AUTO 依据两个信号自适应：
 * - **画面亮度**（Y 平面均值）→ 白天 `vlaneDay` / 夜间 `vlaneNight`
 * - **单帧耗时**（近 20 帧中位数）→ 超过预算降级到半分辨率的 `multitask`
 *
 * 其余三档是手动锁定，方便对比同一场景下不同权重的结果。
 */
internal enum class LaneModelMode {
    AUTO,
    DAY,
    NIGHT,
    LITE,
}
