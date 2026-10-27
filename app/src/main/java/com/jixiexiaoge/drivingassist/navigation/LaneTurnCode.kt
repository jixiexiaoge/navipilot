package com.jixiexiaoge.drivingassist.navigation

/**
 * 高德车道 `drive_way_lane_Back_icon` → Carrot（`carrot_serv._update_tbt`）转向码。
 *
 * ## 为什么不能直接搬导航 ICON 表
 * 车道的 Back_icon 与导航机动 ICON 是**两套独立编码**（早期文档已经踩过这个坑：
 * `direction.toIntOrNull() ?: trafficLaneType` 把高德车道图标 ID 当 CP 转向码发出去，
 * 结果 CP 收到的是另一套语义）。所以这里不复用 `AmapManeuver`，而是复用项目里
 * 已经按语义整理好的 [amapLaneDirection]（数字 0–12 与字符串别名都覆盖）。
 *
 * ## 映射规则
 * 高德一个车道可以同时允许多个方向（如「直行+左转」），而 CP 的 `turn_info[]` 是
 * 每条车道一个 int16，无法表达多方向。因此按「越具体越优先」取一个：
 * 掉头 > 左转 > 右转 > 直行。掉头优先是因为漏提示掉头的代价最大。
 *
 * ## CP 目前怎么用它
 * `cluster_renderer._draw_navi_lane_strip()` 当前只把 `turn_info` 当**车道条数的下限**
 * （`max(count, len(available), len(turn_info))`），并不按码值画箭头。
 * 也就是说发对了暂时不会改变仪表显示，但一旦 CP 开始消费，码值必须是 CP 自己的转向码，
 * 不能是随手透传的高德原始值。
 */
internal object LaneTurnCode {
    /** CP `("notification", "straight", 0)` */
    const val STRAIGHT = 51
    /** CP `("turn", "left", 1)` */
    const val LEFT = 12
    /** CP `("turn", "right", 2)` */
    const val RIGHT = 13
    /** CP `("turn", "uturn", 7)` */
    const val UTURN = 14

    /** 单条车道。无法识别的图标返回 null（调用方据此整键省略，而不是塞 0 或原码）。 */
    fun of(rawIcon: String): Int? = amapLaneDirection(rawIcon)?.let { ofLabel(it.label) }

    /** 按已译好的中文标签取码（[amapLaneDirection] 的唯一输出形态）。 */
    fun ofLabel(label: String): Int? = when {
        label.isBlank() -> null
        "掉头" in label -> UTURN
        "左" in label -> LEFT
        "右" in label -> RIGHT
        "直" in label -> STRAIGHT
        else -> null
    }

    /**
     * 整组翻译：**任一条车道不可识别就整组作废**。
     * 长度错位或部分未知时宁可不发，也不要让 CP 把第 2 条的方向画到第 3 条上。
     */
    fun all(rawIcons: List<String>): List<Int>? {
        if (rawIcons.isEmpty()) return null
        val codes = ArrayList<Int>(rawIcons.size)
        for (icon in rawIcons) codes.add(of(icon) ?: return null)
        return codes
    }
}
