package com.jixiexiaoge.drivingassist.navigation

import com.jixiexiaoge.drivingassist.data.DashboardStore

/**
 * 车端 7711（`selfdrive/carrot/xiaoge_data.py`，Tesla `DAS_road`）的一帧世界量。
 *
 * 与高德 60073 导航灯组是两套语义：
 * - 灯色：`1 红 / 2 绿 / 3 黄`（dashpilot `TL_COLOR_MAP`）。高德 `3` 是「左转绿」，
 *   两者绝不能互灌，这里只按车端码表解释。
 * - [stopLineDistM]：到停止线的米数，车端只在 `DAS_road` 新鲜（1 s）时给值。
 *   它与「灯距」不是同一个点，停止线到灯杆通常还差数米，属于有偏近似值。
 */
data class SignalWorld(
    /** 到停止线距离，米；null = 车端没给（不在路口 / CAN 过期） */
    val stopLineDistM: Float? = null,
    /** 1 红 / 2 绿 / 3 黄；0 或其他 = 无灯 */
    val lightColor: Int = 0,
    /** 距该帧到达的单调毫秒；[Long.MAX_VALUE] 表示未知 */
    val ageMs: Long = Long.MAX_VALUE,
    val receivedAtMs: Long? = null,
) {
    companion object {
        /**
         * 只有 125 米内才认为这条数据与本车当前要过的路口相关。
         * 更远的灯可能不是本车放行相位，宁可退回高德导航灯也不要用错相位。
         *
         * 唯一权威定义在 [com.jixiexiaoge.drivingassist.data.XiaogeBridgeClient.MAX_VALID_DISTANCE_M]，
         * 这里只是转发，避免再次出现三处阈值不一致。
         */
        const val MAX_VALID_DISTANCE_M =
            com.jixiexiaoge.drivingassist.data.XiaogeBridgeClient.MAX_VALID_DISTANCE_M
        const val MAX_AGE_MS = com.jixiexiaoge.drivingassist.data.XiaogeBridgeClient.DATA_TIMEOUT_MS
    }

    val hasLamp: Boolean get() = lightColor in 1..3

    /** 距离是否在有效区间：0 < d <= 125（127 是车端超范围哨兵，自然落在区间外） */
    val distanceValid: Boolean
        get() = (stopLineDistM ?: 0f) in 0.01f..MAX_VALID_DISTANCE_M

    /** 灯色和距离都成立时才允许参与 7714 合成 */
    val usable: Boolean get() = hasLamp && distanceValid && ageMs in 0..MAX_AGE_MS

}

/**
 * 7714 `traffic_signal` 的车端世界量来源。
 *
 * 单独抽成接口是为了让 [NaviStreamManager] 可以在单测里注入固定帧，
 * 不必去碰真实的 7711 连接与单调时钟。
 */
fun interface SignalWorldSource {
    /** 返回 null = 未连接 / 没有新鲜帧 / 已过期 */
    fun latest(): SignalWorld?
}

/** 生产实现：读取 7711 客户端经 [DashboardStore.publishWorld] 写入的最近一帧 */
object StoreSignalWorldSource : SignalWorldSource {
    override fun latest(): SignalWorld? {
        val sample = DashboardStore.latestWorld() ?: return null
        return SignalWorld(
            stopLineDistM = sample.stopLineDistM,
            lightColor = sample.trafficLightColor,
            ageMs = DashboardStore.worldAgeMs(sample),
            receivedAtMs = sample.receivedWallMs,
        )
    }
}
