package com.example.navipilot.navigation

/**
 * 从路口/转向文案推断 nTBTTurnType（与 [TencentNavDataBridge] 文本兜底逻辑对齐）。
 * 放在 main 供高德手机桥接使用，避免 china/global 拆包后 AmapNavDataBridge 依赖腾讯模块。
 */
object TurnTypeTextInference {
    fun inferTurnTypeFromText(text: String): Int {
        if (text.isEmpty()) return -1
        return when {
            text.contains("到达目的地") || text.contains("到达") -> 201
            text.contains("掉头") || text.contains("调头") -> 14
            text.contains("左后方") -> 17
            text.contains("右后方") -> 19
            text.contains("左前方") || text.contains("偏左") -> 102
            text.contains("右前方") || text.contains("偏右") -> 101
            text.contains("靠左") -> 102
            text.contains("靠右") -> 101
            text.contains("左转") -> 12
            text.contains("右转") -> 13
            text.contains("岔路") || text.contains("上高架") || text.contains("走中间") -> 153
            text.contains("直行") || text.contains("继续") -> 51
            text.contains("进入环岛") || text.contains("环岛") && text.contains("进入") -> 131
            text.contains("驶出环岛") || text.contains("环岛") && text.contains("驶出") -> 132
            text.contains("进入隧道") || text.contains("隧道") -> 51
            text.contains("收费站") -> 153
            text.contains("服务区") -> 55
            else -> -1
        }
    }
}
