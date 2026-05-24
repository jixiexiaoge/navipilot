package com.example.navipilot.navigation

import com.example.navipilot.LaneInfo
import timber.log.Timber

/**
 * LaneInfo → 显示箭头映射
 *
 * 将导航 SDK 的车道图标 ID 映射为 UI 显示用的箭头符号。
 * 高德车机广播 (drive_way_lane_Back_icon)、高德手机 SDK、腾讯 SDK、Google SDK
 * 统一通过 [LaneInfo.id] 映射。
 */
object LaneTypeIcon {

    /**
     * 车道类型枚举
     */
    enum class Type(val arrow: String, val label: String) {
        UNKNOWN("━", ""),
        STRAIGHT("▲", "直"),
        LEFT("◄", "左"),
        RIGHT("►", "右"),
        U_TURN("↵", "掉"),
        LEFT_STRAIGHT("◄▲", "左直"),
        RIGHT_STRAIGHT("▲►", "直右"),
        LEFT_RIGHT("◄►", "左右"),
        EMERGENCY("▣", "应急"),
        BUS("▨", "公交");
    }

    /**
     * 将 [LaneInfo] 映射为箭头符号
     */
    fun toArrow(info: LaneInfo): String = toType(info).arrow

    /**
     * 将 [LaneInfo] 映射为车道类型
     *
     * 优先使用 trafficLaneType / trafficLaneExtendedNew 数值判断，
     * 兜底用 id 字符串匹配。
     */
    fun toType(info: LaneInfo): Type {
        // 1) trafficLaneType 数值映射（高德车道线广播）
        val fromType = info.trafficLaneType.let { t ->
            when (t) {
                1 -> Type.STRAIGHT
                2 -> Type.LEFT
                3 -> Type.RIGHT
                4 -> Type.LEFT_STRAIGHT
                5 -> Type.RIGHT_STRAIGHT
                6 -> Type.LEFT_RIGHT
                7 -> Type.U_TURN
                8 -> Type.EMERGENCY
                9 -> Type.BUS
                else -> null
            }
        }
        if (fromType != null) return fromType

        // 2) trafficLaneExtendedNew 数值映射
        val fromExtended = info.trafficLaneExtendedNew.let { t ->
            when (t) {
                1 -> Type.STRAIGHT
                2 -> Type.LEFT
                3 -> Type.RIGHT
                4 -> Type.LEFT_STRAIGHT
                5 -> Type.RIGHT_STRAIGHT
                6 -> Type.LEFT_RIGHT
                7 -> Type.U_TURN
                else -> null
            }
        }
        if (fromExtended != null) return fromExtended

        // 3) id 字符串兜底匹配（关键词包含 + 精确值兜底）
        val idKey = info.id.lowercase().trim()
        return when {
            // 复合方向（优先匹配，避免 single 关键词误命中）
            idKey.contains("straight") && idKey.contains("left") -> Type.LEFT_STRAIGHT
            idKey.contains("straight") && idKey.contains("right") -> Type.RIGHT_STRAIGHT
            idKey.contains("left") && idKey.contains("right") -> Type.LEFT_RIGHT
            // 掉头
            idKey.contains("uturn") || idKey.contains("u_turn") -> Type.U_TURN
            // 特殊车道
            idKey.contains("bus") -> Type.BUS
            idKey.contains("emergency") || idKey.contains("emergency") -> Type.EMERGENCY
            // 单方向
            idKey.contains("straight") || idKey.contains("front") -> Type.STRAIGHT
            idKey.contains("left") -> Type.LEFT
            idKey.contains("right") -> Type.RIGHT
            // 精确值兜底
            idKey in arrayOf("1", "01", "s") -> Type.STRAIGHT
            idKey in arrayOf("2", "02", "10", "l") -> Type.LEFT
            idKey in arrayOf("3", "03", "11", "r") -> Type.RIGHT
            idKey in arrayOf("4", "04", "12") -> Type.LEFT_STRAIGHT
            idKey in arrayOf("5", "05", "13") -> Type.RIGHT_STRAIGHT
            idKey in arrayOf("6", "06") -> Type.LEFT_RIGHT
            idKey in arrayOf("14", "u") -> Type.U_TURN
            else -> {
                Timber.w("LaneTypeIcon_UNKNOWN id='%s' trafficLaneType=%d trafficLaneExtendedNew=%d",
                    info.id, info.trafficLaneType, info.trafficLaneExtendedNew)
                Type.UNKNOWN
            }
        }
    }

    /**
     * 合并多个车道的推荐状态等辅助信息
     */
    data class LaneDisplay(
        val index: Int,
        val type: Type,
        val isRecommended: Boolean,
        val isCurrent: Boolean
    )
}
