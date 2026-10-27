package com.jixiexiaoge.drivingassist

data class AmapManeuverInfo(
    val carrotTurnType: Int,
    val labelZh: String,
    val labelEn: String,
)

data class SegmentAssistantActionInfo(
    val labelZh: String,
    val labelEn: String,
)

enum class AmapIconSource(val identifier: String, val labelZh: String, val labelEn: String) {
    AUTO_BROADCAST("amap_auto_broadcast", "高德车机广播", "AMap Auto broadcast"),
    MOBILE_SDK("amap_mobile_sdk", "高德手机 SDK", "AMap mobile SDK");

    companion object {
        fun fromIdentifier(identifier: String): AmapIconSource? =
            entries.firstOrNull { it.identifier == identifier }
    }
}

/** Source-specific AMap icon mappings verified against the legacy CPdazi mapping document. */
object AmapManeuver {
    private val autoBroadcastIcons = mapOf(
        0 to AmapManeuverInfo(51, "继续/提示", "Continue"),
        1 to AmapManeuverInfo(51, "直行", "Go straight"),
        2 to AmapManeuverInfo(12, "左转", "Turn left"),
        3 to AmapManeuverInfo(13, "右转", "Turn right"),
        4 to AmapManeuverInfo(-1, "左前方", "Keep left"),
        5 to AmapManeuverInfo(-1, "右前方", "Keep right"),
        6 to AmapManeuverInfo(16, "左后方", "Sharp left"),
        7 to AmapManeuverInfo(19, "右后方", "Sharp right"),
        8 to AmapManeuverInfo(14, "掉头", "U-turn"),
        9 to AmapManeuverInfo(51, "直行", "Go straight"),
        10 to AmapManeuverInfo(51, "到达途经点", "Reach waypoint"),
        11 to AmapManeuverInfo(131, "进入环岛", "Enter roundabout"),
        12 to AmapManeuverInfo(132, "驶出环岛", "Exit roundabout"),
        13 to AmapManeuverInfo(55, "服务区", "Service area"),
        14 to AmapManeuverInfo(153, "收费站", "Toll gate"),
        15 to AmapManeuverInfo(201, "到达目的地", "Destination"),
        16 to AmapManeuverInfo(51, "进入隧道", "Enter tunnel"),
        17 to AmapManeuverInfo(140, "左侧通行环岛进入", "Left-drive roundabout entry"),
        18 to AmapManeuverInfo(141, "左侧通行环岛驶出", "Left-drive roundabout exit"),
        19 to AmapManeuverInfo(14, "右转掉头", "Right-turn U-turn"),
        20 to AmapManeuverInfo(51, "顺行", "Continue"),
        // 环岛左右：CP 侧实测 133 = ("rotary","right")、139 = ("rotary","left")，
        // 与「AMap 21=环岛左转 / 22=环岛右转」相反，故此处码值已对调。
        21 to AmapManeuverInfo(139, "环岛左转", "Roundabout left"),
        22 to AmapManeuverInfo(133, "环岛右转", "Roundabout right"),
        23 to AmapManeuverInfo(142, "环岛直行", "Roundabout straight"),
        24 to AmapManeuverInfo(-1, "环岛掉头", "Roundabout U-turn"),
        25 to AmapManeuverInfo(139, "左侧通行环岛左转", "Left-drive roundabout left"),
        26 to AmapManeuverInfo(133, "左侧通行环岛右转", "Left-drive roundabout right"),
        27 to AmapManeuverInfo(142, "左侧通行环岛直行", "Left-drive roundabout straight"),
        28 to AmapManeuverInfo(-1, "左侧通行环岛掉头", "Left-drive roundabout U-turn"),
        55 to AmapManeuverInfo(55, "驶向出口", "Exit ahead"),
        65 to AmapManeuverInfo(-1, "高架靠左", "Elevated keep left"),
        66 to AmapManeuverInfo(101, "高架靠右下匝道", "Elevated exit right"),
        101 to AmapManeuverInfo(101, "右侧辅路", "Right side road"),
    )

    private val mobileSdkIcons = mapOf(
        12 to AmapManeuverInfo(12, "左转", "Turn left"),
        13 to AmapManeuverInfo(12, "左转带角度", "Angled left"),
        14 to AmapManeuverInfo(12, "前方左转", "Left ahead"),
        15 to AmapManeuverInfo(16, "急左转", "Sharp left"),
        16 to AmapManeuverInfo(1000, "左前方", "Keep left"),
        17 to AmapManeuverInfo(13, "右转", "Turn right"),
        18 to AmapManeuverInfo(13, "右转带角度", "Angled right"),
        19 to AmapManeuverInfo(13, "前方右转", "Right ahead"),
        20 to AmapManeuverInfo(19, "急右转", "Sharp right"),
        21 to AmapManeuverInfo(1001, "右前方", "Keep right"),
        22 to AmapManeuverInfo(7, "向左变道", "Change lane left"),
        23 to AmapManeuverInfo(6, "向右变道", "Change lane right"),
        24 to AmapManeuverInfo(102, "靠左出口", "Exit left"),
        25 to AmapManeuverInfo(101, "靠右出口", "Exit right"),
        34 to AmapManeuverInfo(14, "掉头", "U-turn"),
        35 to AmapManeuverInfo(201, "到达目的地", "Destination"),
        36 to AmapManeuverInfo(51, "继续沿路行驶", "Continue"),
        37 to AmapManeuverInfo(154, "进入高速/高架", "Enter highway/elevated"),
    )

    fun info(icon: Int, source: AmapIconSource): AmapManeuverInfo? = when (source) {
        AmapIconSource.AUTO_BROADCAST -> autoBroadcastIcons[icon]
        AmapIconSource.MOBILE_SDK -> mobileSdkIcons[icon]
    }

    /**
     * 高德 ICON → CP（carrot）转向码。
     *
     * **未识别的 ICON 一律返回 -1（= 无有效转向码，上游据此撤销 guidance_current），
     * 绝不能回退成高德原码透传。** 原因：两侧码域完全重叠，CP 侧大量的码都有各自主意
     * （例如未识别的 44 在 CP 里是 fork-left），透传不会报 invalid，而是变成**另一个合法动作**，
     * 比「未知」危险得多。
     *
     * 普通靠左/右不能冒充下匝道；环岛掉头也不能冒充急右转。
     * 没有等义 CP 码时保留本地标签，但撤销 wire guidance。
     */
    fun toCarrotTurnType(icon: Int, source: AmapIconSource): Int =
        info(icon, source)?.carrotTurnType ?: -1

    // This field is separate from ICON. Add values only after matching a real broadcast to AMap UI.
    fun segmentAssistantActionInfo(action: Int): SegmentAssistantActionInfo? = when (action) {
        4 -> SegmentAssistantActionInfo("靠右行驶", "Keep right")
        else -> null
    }
}
