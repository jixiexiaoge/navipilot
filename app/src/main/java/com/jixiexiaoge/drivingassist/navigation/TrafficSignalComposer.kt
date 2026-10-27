package com.jixiexiaoge.drivingassist.navigation

/**
 * CAN 相位优先；CAN 不可用时才回退高德完整灯态。
 * 两源没有同灯关联，不能借用高德方向/倒计时，也不能把停止线距离写成灯距。
 * CAN 黄灯没有协议槽位，撤销而不是用另一来源覆盖已知黄灯。
 */
object TrafficSignalComposer {

    /** 车端码表：1 红 / 2 绿 / 3 黄 */
    private const val CAR_RED = 1
    private const val CAR_GREEN = 2

    /**
     * @param amap 高德 60073 解析出的导航灯组（可能为 null）
     * @param world 车端 7711 世界量（可能为 null / 已过期 / 超距）
     * @return 合成后的灯态；null = 两侧都没有可用相位，整条流应撤销
     */
    fun compose(amap: TrafficSignalData?, world: SignalWorld?): TrafficSignalData? {
        val car = world?.takeIf { it.usable } ?: return amap
        return when (car.lightColor) {
            CAR_RED -> TrafficSignalData(
                visible = true,
                redValid = true,
                redOn = true,
            )
            CAR_GREEN -> TrafficSignalData(visible = true, greenValid = true, greenOn = true)
            else -> null
        }
    }

    /** 诊断用：这一帧最终采用的是哪一侧 */
    fun chosenSource(amap: TrafficSignalData?, world: SignalWorld?): String = when {
        world?.usable == true && world.lightColor == CAR_RED -> "car"
        world?.usable == true && world.lightColor == CAR_GREEN -> "car"
        world?.usable == true -> "car_yellow_unsupported"
        amap != null -> "amap"
        world?.hasLamp == true && !world.distanceValid -> "car_out_of_range"
        else -> "none"
    }
}
